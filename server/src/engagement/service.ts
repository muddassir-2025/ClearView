import { env } from '../env.js';
import { isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, forbidden, notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';
import { roleOf as channelRoleOf, type ChannelRole } from '../channels/service.js';

/**
 * Engagement: reactions (§13), polls (§14) and post views (§15).
 *
 * Three rules run through everything here.
 *
 *  * **Aggregates out, identities never.** Every read returns counts and, only
 *    for the caller, their own choice. There is no function in this module that
 *    can answer "who reacted" — not because a caller would not ask, but because
 *    the only way to be certain §13 and §14 hold is for the capability to not
 *    exist.
 *
 *  * **Interacting requires following.** §12 makes following the act of joining
 *    a channel, and §13/§14 are a follower's actions inside it. The check is
 *    therefore the same one everywhere, in [loadInteractablePost], rather than
 *    repeated per endpoint where one copy would eventually be forgotten.
 *
 *  * **A repeat is not a new event.** One reaction row per person per post
 *    changes by UPDATE, and a view increments its counter only outside
 *    VIEW_DEDUPE_WINDOW_MINUTES. §15 asks for exactly this: a scroll loop must
 *    not be able to inflate a channel's numbers.
 */

export type ReactionKind = 'like' | 'love' | 'laugh' | 'wow' | 'sad' | 'angry';

/**
 * The accepted set, in the order a client should offer them.
 *
 * Compiled in and mirrored by the `reaction_kind` enum: a reaction arrives from
 * a JSON body, so it needs validating before the database sees it, and an
 * unknown value must be a wordable 400 rather than a constraint violation the
 * client cannot act on.
 */
export const REACTIONS: readonly ReactionKind[] = ['like', 'love', 'laugh', 'wow', 'sad', 'angry'];

export function isReactionKind(value: unknown): value is ReactionKind {
  return typeof value === 'string' && (REACTIONS as readonly string[]).includes(value);
}

/** Roles that may see a channel's analytics (§15). */
const ANALYTICS_ROLES: readonly ChannelRole[] = ['owner', 'editor'];

export interface ReactionCount {
  readonly reaction: ReactionKind;
  readonly count: number;
}

export interface PollOptionResult {
  readonly id: string;
  readonly position: number;
  readonly label: string;
  readonly votes: number;
}

/**
 * A poll as a reader sees it (§14).
 *
 * `totalVotes` counts SELECTIONS, not people: in a multiple-choice poll one
 * voter can contribute several. Reporting it as "voters" would overstate
 * participation, so the name says what it is.
 *
 * `viewerVotes` is the one piece of per-viewer data in the shape, and it is the
 * caller's own — which is what lets the client render "you chose B" without
 * ever telling it who else did.
 */
export interface PollView {
  readonly id: string;
  readonly question: string;
  readonly allowMultiple: boolean;
  readonly closesAt: string | null;
  readonly isClosed: boolean;
  readonly totalVotes: number;
  readonly options: readonly PollOptionResult[];
  readonly viewerVotes: readonly string[];
}

export interface PostEngagement {
  readonly reactions: readonly ReactionCount[];
  readonly reactionTotal: number;
  readonly viewerReaction: ReactionKind | null;
  /** §15's headline number: distinct accounts that have opened the post. */
  readonly uniqueViewers: number;
  /** Looks, capped by the dedupe window. Never below `uniqueViewers`. */
  readonly totalViews: number;
  readonly viewerHasViewed: boolean;
  readonly poll: PollView | null;
}

interface PostVisibilityRow {
  id: string;
  channel_id: string;
  channel_status: string;
}

/**
 * Load a post the caller is entitled to interact with, or throw the reason not.
 *
 * The order is deliberate: existence, then the channel's moderation state, then
 * the caller's block, then the follow. Reversed, a stranger who guessed a post
 * id could learn that a channel is suspended — moderation state they have no
 * business reading — and a blocked user would be told to follow a channel they
 * deliberately blocked.
 *
 * `post_not_found` for a deleted post rather than "it was deleted": §25's
 * removal is not something a reader is owed an explanation of, and the same
 * answer covers a post that never existed.
 */
export async function loadInteractablePost(
  database: Queryable,
  userId: string,
  postId: string
): Promise<PostVisibilityRow> {
  if (!isUuid(postId)) throw notFound('post_not_found');

  const row = await database.queryOne<PostVisibilityRow>(
    `SELECT p.id, p.channel_id, c.status AS channel_status
       FROM posts p
       JOIN channels c ON c.id = p.channel_id
      WHERE p.id = $1 AND p.deleted_at IS NULL AND c.deleted_at IS NULL`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');

  if (row.channel_status !== 'active') {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }

  const blocked = await database.queryOne(
    `SELECT 1 FROM channel_blocks WHERE channel_id = $1 AND user_id = $2`,
    [row.channel_id, userId]
  );
  if (blocked) {
    throw forbidden('channel_blocked', 'Unblock this channel to interact with its posts.');
  }

  const following = await database.queryOne(
    `SELECT 1 FROM channel_followers WHERE channel_id = $1 AND user_id = $2`,
    [row.channel_id, userId]
  );
  if (!following) {
    throw forbidden('not_following', 'Follow this channel to interact with its posts.');
  }

  return row;
}

/**
 * Aggregate counts for a page of posts, grouped by reaction.
 *
 * One query for the whole page rather than one per post: a 30-post feed would
 * otherwise make 30 round trips, which is the shape that makes a feature like
 * this feel slow on a phone.
 */
async function reactionCounts(
  database: Queryable,
  postIds: readonly string[]
): Promise<Map<string, ReactionCount[]>> {
  const byPost = new Map<string, ReactionCount[]>();
  if (postIds.length === 0) return byPost;

  const rows = await database.query<{ post_id: string; reaction: ReactionKind; count: number }>(
    `SELECT post_id, reaction, count(*)::int AS count
       FROM post_reactions
      WHERE post_id = ANY($1::uuid[])
      GROUP BY post_id, reaction
      ORDER BY post_id, reaction`,
    [postIds]
  );

  for (const row of rows) {
    const list = byPost.get(row.post_id) ?? [];
    list.push({ reaction: row.reaction, count: row.count });
    byPost.set(row.post_id, list);
  }
  return byPost;
}

/** The caller's OWN reaction per post, so the client can highlight it. */
async function viewerReactions(
  database: Queryable,
  userId: string,
  postIds: readonly string[]
): Promise<Map<string, ReactionKind>> {
  const byPost = new Map<string, ReactionKind>();
  if (postIds.length === 0) return byPost;

  const rows = await database.query<{ post_id: string; reaction: ReactionKind }>(
    `SELECT post_id, reaction FROM post_reactions
      WHERE user_id = $1 AND post_id = ANY($2::uuid[])`,
    [userId, postIds]
  );
  for (const row of rows) byPost.set(row.post_id, row.reaction);
  return byPost;
}

interface ViewRow {
  post_id: string;
  unique_viewers: number;
  total_views: number;
}

async function viewCounts(
  database: Queryable,
  postIds: readonly string[]
): Promise<Map<string, { uniqueViewers: number; totalViews: number }>> {
  const byPost = new Map<string, { uniqueViewers: number; totalViews: number }>();
  if (postIds.length === 0) return byPost;

  const rows = await database.query<ViewRow>(
    `SELECT post_id, count(*)::int AS unique_viewers, COALESCE(sum(view_count), 0)::int AS total_views
       FROM post_views
      WHERE post_id = ANY($1::uuid[])
      GROUP BY post_id`,
    [postIds]
  );
  for (const row of rows) {
    byPost.set(row.post_id, {
      uniqueViewers: row.unique_viewers,
      totalViews: row.total_views,
    });
  }
  return byPost;
}

async function viewedPosts(
  database: Queryable,
  userId: string,
  postIds: readonly string[]
): Promise<Set<string>> {
  const viewed = new Set<string>();
  if (postIds.length === 0) return viewed;

  const rows = await database.query<{ post_id: string }>(
    `SELECT post_id FROM post_views WHERE user_id = $1 AND post_id = ANY($2::uuid[])`,
    [userId, postIds]
  );
  for (const row of rows) viewed.add(row.post_id);
  return viewed;
}

interface PollRow {
  id: string;
  post_id: string;
  question: string;
  allow_multiple: boolean;
  closes_at: unknown;
  option_id: string;
  position: number;
  label: string;
  votes: number;
}

/**
 * Polls for a page of posts, options and per-option counts in one query.
 *
 * The count is a correlated subquery rather than a LEFT JOIN with a GROUP BY:
 * joining votes would multiply the option rows by their votes and require the
 * grouping to undo it, and a mistake there silently mis-reports one option's
 * total as another's.
 */
async function pollRows(
  database: Queryable,
  postIds: readonly string[]
): Promise<Map<string, PollView>> {
  const byPost = new Map<string, PollView>();
  if (postIds.length === 0) return byPost;

  const rows = await database.query<PollRow>(
    `SELECT pl.id, pl.post_id, pl.question, pl.allow_multiple, pl.closes_at,
            o.id AS option_id, o.position, o.label,
            (SELECT count(*)::int FROM poll_votes v WHERE v.option_id = o.id) AS votes
       FROM polls pl
       JOIN poll_options o ON o.poll_id = pl.id
      WHERE pl.post_id = ANY($1::uuid[])
      ORDER BY pl.id, o.position`,
    [postIds]
  );

  // Built mutable and frozen into the readonly shape at the end. The rows
  // arrive one per OPTION, so the poll has to be accumulated across rows; a
  // readonly target would need casts at every push, which is how a real type
  // error gets silenced later.
  interface Accumulated {
    id: string;
    question: string;
    allowMultiple: boolean;
    closesAt: string | null;
    totalVotes: number;
    options: PollOptionResult[];
  }
  const accumulated = new Map<string, Accumulated>();

  for (const row of rows) {
    const option: PollOptionResult = {
      id: row.option_id,
      position: row.position,
      label: row.label,
      votes: row.votes,
    };

    const existing = accumulated.get(row.post_id);
    if (existing) {
      existing.options.push(option);
      existing.totalVotes += option.votes;
      continue;
    }

    const closesAt = isoOrNull(row.closes_at);
    accumulated.set(row.post_id, {
      id: row.id,
      question: row.question,
      allowMultiple: row.allow_multiple,
      closesAt,
      totalVotes: option.votes,
      options: [option],
    });
  }

  for (const [postId, poll] of accumulated) {
    byPost.set(postId, {
      id: poll.id,
      question: poll.question,
      allowMultiple: poll.allowMultiple,
      closesAt: poll.closesAt,
      isClosed: isClosed(poll.closesAt),
      totalVotes: poll.totalVotes,
      options: poll.options,
      // Filled by [viewerPollVotes], which the caller runs once for the page.
      viewerVotes: [],
    });
  }

  return byPost;
}

/** A poll with no close time never closes; one with a past time is closed. */
function isClosed(closesAt: string | null): boolean {
  if (closesAt === null) return false;
  const at = Date.parse(closesAt);
  return Number.isFinite(at) && at <= Date.now();
}

/** The caller's own votes, keyed by poll. */
async function viewerPollVotes(
  database: Queryable,
  userId: string,
  pollIds: readonly string[]
): Promise<Map<string, string[]>> {
  const byPoll = new Map<string, string[]>();
  if (pollIds.length === 0) return byPoll;

  const rows = await database.query<{ poll_id: string; option_id: string }>(
    `SELECT poll_id, option_id FROM poll_votes
      WHERE user_id = $1 AND poll_id = ANY($2::uuid[])
      ORDER BY created_at`,
    [userId, pollIds]
  );
  for (const row of rows) {
    const list = byPoll.get(row.poll_id) ?? [];
    list.push(row.option_id);
    byPoll.set(row.poll_id, list);
  }
  return byPoll;
}

/**
 * Everything the feed needs to render engagement for a page of posts, in four
 * queries rather than four per post.
 *
 * A post with no engagement still gets an entry, so a client can render the
 * zero state without a null check per field.
 */
export async function engagementForPosts(
  database: Queryable,
  userId: string,
  postIds: readonly string[]
): Promise<Map<string, PostEngagement>> {
  const result = new Map<string, PostEngagement>();

  const [reactions, mine, views, viewed, polls] = await Promise.all([
    reactionCounts(database, postIds),
    viewerReactions(database, userId, postIds),
    viewCounts(database, postIds),
    viewedPosts(database, userId, postIds),
    pollRows(database, postIds),
  ]);

  const pollVotes = await viewerPollVotes(
    database,
    userId,
    [...polls.values()].map((poll) => poll.id)
  );

  for (const postId of postIds) {
    const counts = reactions.get(postId) ?? [];
    const view = views.get(postId);
    const poll = polls.get(postId) ?? null;

    result.set(postId, {
      reactions: counts,
      reactionTotal: counts.reduce((sum, entry) => sum + entry.count, 0),
      viewerReaction: mine.get(postId) ?? null,
      uniqueViewers: view?.uniqueViewers ?? 0,
      totalViews: view?.totalViews ?? 0,
      viewerHasViewed: viewed.has(postId),
      poll: poll === null ? null : { ...poll, viewerVotes: pollVotes.get(poll.id) ?? [] },
    });
  }

  return result;
}

export interface ReactionState {
  readonly reaction: ReactionKind | null;
  readonly reactions: readonly ReactionCount[];
}

/**
 * Set, change or clear the caller's reaction (§13).
 *
 * `null` clears it, because a reaction that cannot be taken back is a UI that
 * lies about what it will do. Both directions return the fresh counts, so the
 * client never has to guess or re-fetch.
 */
export async function setReaction(
  database: Queryable,
  userId: string,
  postId: string,
  reaction: ReactionKind | null
): Promise<ReactionState> {
  await loadInteractablePost(database, userId, postId);

  if (reaction === null) {
    await database.query(`DELETE FROM post_reactions WHERE post_id = $1 AND user_id = $2`, [
      postId,
      userId,
    ]);
  } else {
    // Upsert: changing your mind is one row, which is what makes the aggregate
    // a count of people rather than of taps.
    await database.query(
      `INSERT INTO post_reactions (post_id, user_id, reaction)
       VALUES ($1, $2, $3)
       ON CONFLICT (post_id, user_id)
       DO UPDATE SET reaction = EXCLUDED.reaction, updated_at = now()`,
      [postId, userId, reaction]
    );
  }

  const [counts, current] = await Promise.all([
    reactionCounts(database, [postId]),
    viewerReactions(database, userId, [postId]),
  ]);

  return {
    reaction: current.get(postId) ?? null,
    reactions: counts.get(postId) ?? [],
  };
}

export interface ViewState {
  readonly uniqueViewers: number;
  readonly totalViews: number;
  /** False when the look fell inside the dedupe window and changed nothing. */
  readonly counted: boolean;
  readonly viewerHasViewed: boolean;
}

/**
 * Record that the caller opened a post (§15).
 *
 * The `ON CONFLICT ... WHERE` is the dedupe: an existing row is only touched
 * when its `last_viewed_at` is older than the window, so a client that pings on
 * every scroll (or on every recomposition) cannot move the number at all. The
 * `INSERT ... SELECT` trick would not work here because the condition belongs
 * to the update, not to the row's existence.
 *
 * Since the statement is a no-op when it declines to count, `counted` is
 * decided by whether a row came back — never assumed.
 */
export async function recordView(
  database: Queryable,
  userId: string,
  postId: string
): Promise<ViewState> {
  await loadInteractablePost(database, userId, postId);

  const updated = await database.query<{ view_count: number }>(
    `INSERT INTO post_views (post_id, user_id)
     VALUES ($1, $2)
     ON CONFLICT (post_id, user_id) DO UPDATE
       SET view_count = post_views.view_count + 1,
           last_viewed_at = now()
       WHERE post_views.last_viewed_at <= now() - ($3::int * interval '1 minute')
     RETURNING view_count`,
    [postId, userId, env.VIEW_DEDUPE_WINDOW_MINUTES]
  );

  const counts = await viewCounts(database, [postId]);
  const view = counts.get(postId) ?? { uniqueViewers: 0, totalViews: 0 };

  return {
    uniqueViewers: view.uniqueViewers,
    totalViews: view.totalViews,
    counted: updated.length > 0,
    viewerHasViewed: true,
  };
}

export interface CreatePollInput {
  readonly question: string;
  readonly options: readonly string[];
  readonly allowMultiple?: boolean | undefined;
  /** Hours until the poll closes; omitted means it never closes. */
  readonly openForHours?: number | undefined;
}

/**
 * Normalise and validate poll input.
 *
 * Separate from the insert so the publish path can reject a malformed poll
 * BEFORE it begins the transaction that inserts the post — a poll that fails
 * validation after the post exists would roll the whole thing back, which works
 * but turns a simple 400 into a transaction that did work for nothing.
 */
export function normalisePollInput(input: CreatePollInput): {
  question: string;
  options: string[];
  allowMultiple: boolean;
  closesAt: Date | null;
} {
  const question = input.question.trim();
  if (question.length === 0 || question.length > 300) {
    throw badRequest('invalid_poll', 'A poll question must be between 1 and 300 characters.');
  }

  const options = input.options.map((option) => option.trim());
  if (options.length < env.POLL_MIN_OPTIONS) {
    throw badRequest('too_few_options', `A poll needs at least ${env.POLL_MIN_OPTIONS} options.`);
  }
  if (options.length > env.POLL_MAX_OPTIONS) {
    throw badRequest('too_many_options', `A poll can hold at most ${env.POLL_MAX_OPTIONS} options.`);
  }
  if (options.some((option) => option.length === 0 || option.length > 120)) {
    throw badRequest('invalid_option', 'Each option must be between 1 and 120 characters.');
  }
  // Compared case-insensitively because that is what the database's unique
  // index enforces; a check that disagreed with it would turn a client bug into
  // a 500 from a violated constraint.
  if (new Set(options.map((option) => option.toLowerCase())).size !== options.length) {
    throw badRequest('duplicate_option', 'Two options cannot have the same text.');
  }

  if (input.openForHours !== undefined) {
    if (!Number.isFinite(input.openForHours) || input.openForHours <= 0) {
      throw badRequest('invalid_poll_window', 'openForHours must be a positive number of hours.');
    }
  }

  return {
    question,
    options,
    allowMultiple: input.allowMultiple === true,
    closesAt:
      input.openForHours === undefined
        ? null
        : new Date(Date.now() + input.openForHours * 3_600_000),
  };
}

/**
 * Insert a poll for a post, inside the caller's transaction.
 *
 * Takes a `tx` rather than a `Queryable` on purpose: a poll is part of
 * publishing, and a post that exists without the poll it promised — or a poll
 * whose options were only half written — would be a post no client can render.
 */
export async function createPollInTransaction(
  tx: Queryable,
  postId: string,
  poll: { question: string; options: string[]; allowMultiple: boolean; closesAt: Date | null }
): Promise<string> {
  const inserted = await tx.query<{ id: string }>(
    `INSERT INTO polls (post_id, question, allow_multiple, closes_at)
     VALUES ($1, $2, $3, $4)
     RETURNING id`,
    [postId, poll.question, poll.allowMultiple, poll.closesAt]
  );
  const pollId = one(inserted).id;

  // One statement for all options: a loop would be N round trips inside a
  // transaction that holds locks on the post's media rows.
  await tx.query(
    `INSERT INTO poll_options (poll_id, position, label)
     SELECT $1, (ord - 1)::int, value
       FROM unnest($2::text[]) WITH ORDINALITY AS t(value, ord)`,
    [pollId, poll.options]
  );

  return pollId;
}

interface PollOwnerRow {
  id: string;
  post_id: string;
  question: string;
  allow_multiple: boolean;
  closes_at: unknown;
}

/**
 * Vote in a poll (§14).
 *
 * Replaces the caller's selections wholesale rather than adding to them. That
 * one rule covers all three cases the product needs — vote, change a
 * single-choice vote, and adjust a multiple-choice one — with no mode flag, and
 * it cannot leave a stale selection behind the way an incremental insert would
 * when a user switches a choice.
 */
export async function voteInPoll(
  database: Queryable,
  userId: string,
  pollId: string,
  optionIds: readonly string[]
): Promise<PollView> {
  if (!isUuid(pollId)) throw notFound('poll_not_found');

  const poll = await database.queryOne<PollOwnerRow>(
    `SELECT id, post_id, question, allow_multiple, closes_at FROM polls WHERE id = $1`,
    [pollId]
  );
  if (!poll) throw notFound('poll_not_found');

  // Interacting with a poll is interacting with its post, so it carries the
  // same follow/block/status gate rather than a second, looser one.
  await loadInteractablePost(database, userId, poll.post_id);

  if (isClosed(isoOrNull(poll.closes_at))) {
    throw badRequest('poll_closed', 'This poll has closed.');
  }

  const unique = [...new Set(optionIds)];
  if (unique.length === 0) {
    throw badRequest('no_votes', 'Select at least one option.');
  }
  if (!poll.allow_multiple && unique.length > 1) {
    throw badRequest('single_choice_only', 'This poll accepts one answer.');
  }

  const valid = await database.query<{ id: string }>(
    `SELECT id FROM poll_options WHERE poll_id = $1 AND id = ANY($2::uuid[])`,
    [pollId, unique]
  );
  if (valid.length !== unique.length) {
    // One message for "not an option" and "an option of another poll": the
    // caller is not entitled to learn which option ids exist elsewhere.
    throw badRequest('unknown_option', 'One of those options is not part of this poll.');
  }

  await database.transaction(async (tx) => {
    await tx.query(
      `DELETE FROM poll_votes
        WHERE poll_id = $1 AND user_id = $2 AND option_id <> ALL($3::uuid[])`,
      [pollId, userId, unique]
    );
    await tx.query(
      `INSERT INTO poll_votes (poll_id, option_id, user_id)
       SELECT $1, option_id, $2 FROM unnest($3::uuid[]) AS option_id
       ON CONFLICT (poll_id, option_id, user_id) DO NOTHING`,
      [pollId, userId, unique]
    );
  });

  const polls = await pollRows(database, [poll.post_id]);
  const votes = await viewerPollVotes(database, userId, [pollId]);
  const view = polls.get(poll.post_id);
  if (!view) throw notFound('poll_not_found');
  return { ...view, viewerVotes: votes.get(pollId) ?? [] };
}

export interface ChannelAnalyticsTotals {
  readonly followers: number;
  readonly posts: number;
  readonly uniqueViewers: number;
  readonly totalViews: number;
  readonly reactions: number;
  readonly pollVotes: number;
}

export interface ChannelAnalyticsPoint {
  readonly day: string;
  readonly views: number;
  readonly newFollowers: number;
}

export interface ChannelAnalyticsPost {
  readonly postId: string;
  readonly type: string;
  readonly createdAt: string;
  readonly uniqueViewers: number;
  readonly totalViews: number;
  readonly reactions: number;
}

export interface ChannelAnalytics {
  readonly channelId: string;
  readonly windowDays: number;
  readonly totals: ChannelAnalyticsTotals;
  readonly series: readonly ChannelAnalyticsPoint[];
  readonly topPosts: readonly ChannelAnalyticsPost[];
}

/**
 * A channel's own engagement numbers (§15).
 *
 * Owner- and editor-only, checked here from `channel_admins` — analytics is
 * management data, and §15 says plainly not to expose it to followers. The
 * responder role is excluded for the same reason it cannot publish: answering
 * follower messages is not the same power as seeing how a channel is doing.
 *
 * The series is generated by the database rather than assembled in Node, so a
 * day with no activity is a zero in the result rather than a missing point the
 * client would have to invent — a gap in a chart is a lie about what happened.
 */
export async function channelAnalytics(
  database: Queryable,
  userId: string,
  channelId: string,
  days?: number
): Promise<ChannelAnalytics> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const role = await channelRoleOf(database, channelId, userId);
  if (role === null || !ANALYTICS_ROLES.includes(role)) {
    throw forbidden('analytics_forbidden', 'Only a channel admin can see its analytics.');
  }

  const windowDays = days && days > 0 ? Math.min(days, 365) : env.ANALYTICS_WINDOW_DAYS;

  const totals = await database.queryOne<{
    followers: number;
    posts: number;
    reactions: number;
    poll_votes: number;
  }>(
    `SELECT
       (SELECT count(*)::int FROM channel_followers f WHERE f.channel_id = $1) AS followers,
       (SELECT count(*)::int FROM posts p
         WHERE p.channel_id = $1 AND p.deleted_at IS NULL) AS posts,
       (SELECT count(*)::int FROM post_reactions r
          JOIN posts p ON p.id = r.post_id
         WHERE p.channel_id = $1 AND p.deleted_at IS NULL) AS reactions,
       (SELECT count(*)::int FROM poll_votes v
          JOIN polls pl ON pl.id = v.poll_id
          JOIN posts p ON p.id = pl.post_id
         WHERE p.channel_id = $1 AND p.deleted_at IS NULL) AS poll_votes`,
    [channelId]
  );

  const views = await database.queryOne<{ unique_viewers: number; total_views: number }>(
    `SELECT count(*)::int AS unique_viewers,
            COALESCE(sum(v.view_count), 0)::int AS total_views
       FROM post_views v
       JOIN posts p ON p.id = v.post_id
      WHERE p.channel_id = $1 AND p.deleted_at IS NULL`,
    [channelId]
  );

  const series = await database.query<{ day: string; views: number; new_followers: number }>(
    `SELECT to_char(d, 'YYYY-MM-DD') AS day,
            (SELECT count(*)::int FROM post_views v
               JOIN posts p ON p.id = v.post_id
              WHERE p.channel_id = $1 AND p.deleted_at IS NULL
                AND v.first_viewed_at >= d AND v.first_viewed_at < d + interval '1 day') AS views,
            (SELECT count(*)::int FROM channel_followers f
              WHERE f.channel_id = $1
                AND f.followed_at >= d AND f.followed_at < d + interval '1 day') AS new_followers
       FROM generate_series(
              date_trunc('day', now()) - make_interval(days => $2::int - 1),
              date_trunc('day', now()),
              interval '1 day'
            ) AS d
      ORDER BY d`,
    [channelId, windowDays]
  );

  const topPosts = await database.query<{
    post_id: string;
    type: string;
    created_at: unknown;
    unique_viewers: number;
    total_views: number;
    reactions: number;
  }>(
    `SELECT p.id AS post_id,
            p.type,
            p.created_at,
            (SELECT count(*)::int FROM post_views v WHERE v.post_id = p.id) AS unique_viewers,
            (SELECT COALESCE(sum(v.view_count), 0)::int FROM post_views v WHERE v.post_id = p.id)
              AS total_views,
            (SELECT count(*)::int FROM post_reactions r WHERE r.post_id = p.id) AS reactions
       FROM posts p
      WHERE p.channel_id = $1 AND p.deleted_at IS NULL
      ORDER BY unique_viewers DESC, p.created_at DESC
      LIMIT 10`,
    [channelId]
  );

  return {
    channelId,
    windowDays,
    totals: {
      followers: totals?.followers ?? 0,
      posts: totals?.posts ?? 0,
      uniqueViewers: views?.unique_viewers ?? 0,
      totalViews: views?.total_views ?? 0,
      reactions: totals?.reactions ?? 0,
      pollVotes: totals?.poll_votes ?? 0,
    },
    series: series.map((point) => ({
      day: point.day,
      views: point.views,
      newFollowers: point.new_followers,
    })),
    topPosts: topPosts.map((row) => ({
      postId: row.post_id,
      type: row.type,
      createdAt: isoOrNull(row.created_at) ?? '',
      uniqueViewers: row.unique_viewers,
      totalViews: row.total_views,
      reactions: row.reactions,
    })),
  };
}
