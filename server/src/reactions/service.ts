import type { Queryable } from '../db.js';
import { notFound } from '../http/errors.js';
import { isUuid } from '../channels/cursor.js';

/**
 * Reactions on a post (§9).
 *
 * A reader picks one emoji per post; the post carries how many readers picked
 * each. This module owns the whole vocabulary and both directions of the read:
 * counts for a page of posts (public, no reader involved) and the caller's own
 * reactions (reader-scoped, never another reader's).
 *
 * ## What a caller can and cannot learn here
 *
 * [reactionsForPosts] answers a count per emoji, which is all a card shows, and
 * it never returns WHO reacted — there is no query in this module for that, and
 * adding one would be a new decision rather than a new parameter.
 * [listReaderReactions] is scoped to one reader id, which the caller has already
 * proved they are.
 *
 * ## Why the vocabulary is a constant and not a parameter
 *
 * The six emoji are part of the API's contract: the client draws exactly these,
 * and `post_reactions_emoji_known` refuses anything else at the database. Having
 * one list here — rather than a regex in the route, a list in the client and a
 * CHECK in SQL that drift — is what keeps a seventh emoji a one-line change in
 * three places instead of a mystery of missing counts.
 */
export const REACTION_EMOJI = ['👍', '❤️', '😂', '😮', '😢', '🙏'] as const;

export type ReactionEmoji = (typeof REACTION_EMOJI)[number];

/** One emoji and how many readers chose it. */
export interface ReactionCount {
  readonly emoji: string;
  readonly count: number;
}

/** A reader's own reaction to one post, or its absence. */
export interface ReaderReaction {
  readonly postId: string;
  readonly emoji: string | null;
  /** How many readers now hold THIS emoji on this post, after the change. */
  readonly count: number;
}

/**
 * Whether [value] is one of the six.
 *
 * A type guard rather than an `as` at the route: the value arrives from a
 * request body, and a body is exactly the place a wrong type used to be a
 * compile-time certainty about a runtime value.
 */
export function isReactionEmoji(value: unknown): value is ReactionEmoji {
  return typeof value === 'string' && (REACTION_EMOJI as readonly string[]).includes(value);
}

/**
 * How many rows a single reader's reaction read will return.
 *
 * A reader who has reacted in a channel has reacted to a handful of its posts,
 * so the real number is tiny; the cap exists so that a reader with a very long
 * history in a very chatty channel cannot turn one screen open into an unbounded
 * transfer. Newest first, so the cap drops the oldest reactions — which are the
 * ones least likely to be on screen.
 */
const MAX_READER_REACTIONS = 500;

/**
 * The posts a reader has reacted to, in one channel, and with what.
 *
 * The reader's own state, so it is never merged into the public payload: that
 * surface takes no token and must stay answerable with none. The client asks for
 * this when it opens a channel and merges the two.
 *
 * Scoped by channel because a reader's reactions across the whole product is a
 * history of what they have read, which is the opposite of the privacy stance
 * this API takes elsewhere. A channel at a time is what the UI needs.
 */
export async function listReaderReactions(
  database: Queryable,
  readerId: string,
  channelId: string
): Promise<Record<string, string>> {
  const rows = await database.query<{ post_id: string; emoji: string }>(
    `SELECT r.post_id, r.emoji
       FROM post_reactions r
       JOIN posts p ON p.id = r.post_id
      WHERE r.reader_id = $1
        AND p.channel_id = $2
        AND p.deleted_at IS NULL
      ORDER BY p.created_at DESC
      LIMIT ${MAX_READER_REACTIONS}`,
    [readerId, channelId]
  );

  return Object.fromEntries(rows.map((row) => [row.post_id, row.emoji]));
}

/**
 * Set (or replace) the caller's reaction to a post.
 *
 * One statement, and the conflict clause is the whole feature: reacting a second
 * time UPDATES the emoji rather than adding a row, so "one reaction per reader"
 * is the primary key's business and not a rule this function has to remember.
 *
 * The post must be one a reader can actually see: not soft-deleted, in a channel
 * that is active. Checked here rather than trusted from the caller, and checked
 * BEFORE the write, so a stale id cannot leave a reaction on a removed post.
 */
export async function setPostReaction(
  database: Queryable,
  readerId: string,
  postId: string,
  emoji: ReactionEmoji
): Promise<ReaderReaction> {
  await requireVisiblePost(database, postId);

  await database.query(
    `INSERT INTO post_reactions (post_id, reader_id, emoji)
     VALUES ($1, $2, $3)
     ON CONFLICT (post_id, reader_id)
     DO UPDATE SET emoji = EXCLUDED.emoji, updated_at = now()`,
    [postId, readerId, emoji]
  );

  return {
    postId,
    emoji,
    count: await countOf(database, postId, emoji),
  };
}

/**
 * Take the caller's reaction back off a post.
 *
 * Idempotent, and it answers a count rather than 404-ing when there was nothing
 * to remove: a client that retries an un-react (or a reader who taps the chip
 * that is already off) is not making a mistake, and there is no state for the
 * answer to leak — the caller either has a reaction or does not.
 */
export async function clearPostReaction(
  database: Queryable,
  readerId: string,
  postId: string,
  emoji: string | null
): Promise<ReaderReaction> {
  await database.query(
    `DELETE FROM post_reactions WHERE post_id = $1 AND reader_id = $2`,
    [postId, readerId]
  );

  // The count of the emoji that WAS there, which is what the card is showing and
  // therefore the number it needs to correct. An unknown emoji (the client did
  // not say, or said something else) falls back to the reader's own former
  // choice, and to nothing when there was none — never to a guess.
  const previous = emoji ?? (await previousEmoji(database, readerId, postId));
  return {
    postId,
    emoji: null,
    count: previous === null ? 0 : await countOf(database, postId, previous),
  };
}

/**
 * How many readers hold each emoji on each of [postIds].
 *
 * One query for a whole page, like the media read beside it, because a page is
 * 20 posts and 20 round trips to draw a card is how a feed becomes slow.
 *
 * Grouped in SQL rather than counted in the application: the rows never have to
 * travel, and a post with ten thousand reactions costs this query the same as a
 * post with one.
 */
export async function reactionsForPosts(
  database: Queryable,
  postIds: readonly string[]
): Promise<Map<string, ReactionCount[]>> {
  const byPost = new Map<string, ReactionCount[]>();
  if (postIds.length === 0) return byPost;

  // Placeholders rather than an array parameter, for the same reason
  // `mediaForPosts` uses them: the list is bounded by MAX_PAGE_SIZE, and an
  // explicit list behaves identically on `pg` and on PGlite (the test database).
  const placeholders = postIds.map((_, i) => `$${i + 1}`).join(', ');
  const rows = await database.query<{ post_id: string; emoji: string; count: string | number }>(
    `SELECT post_id, emoji, count(*)::int AS count
       FROM post_reactions
      WHERE post_id IN (${placeholders})
      GROUP BY post_id, emoji
      ORDER BY post_id, count(*) DESC, emoji`,
    postIds
  );

  for (const row of rows) {
    const list = byPost.get(row.post_id) ?? [];
    list.push({ emoji: row.emoji, count: Number(row.count) });
    byPost.set(row.post_id, list);
  }
  return byPost;
}

/** How many readers hold [emoji] on [postId]. */
async function countOf(database: Queryable, postId: string, emoji: string): Promise<number> {
  const row = await database.queryOne<{ count: string | number }>(
    `SELECT count(*)::int AS count FROM post_reactions WHERE post_id = $1 AND emoji = $2`,
    [postId, emoji]
  );
  return Number(row?.count ?? 0);
}

/** The caller's current emoji on a post, if any. */
async function previousEmoji(
  database: Queryable,
  readerId: string,
  postId: string
): Promise<string | null> {
  const row = await database.queryOne<{ emoji: string }>(
    `SELECT emoji FROM post_reactions WHERE post_id = $1 AND reader_id = $2`,
    [postId, readerId]
  );
  return row?.emoji ?? null;
}

/**
 * The post's channel, or a 404.
 *
 * The same two conditions every reader-facing post read applies (`deleted_at IS
 * NULL`, an active channel), so a reaction cannot exist on something a reader
 * could not open. A uuid that is not a uuid, a post that does not exist, a
 * removed post and a suspended channel all answer `post_not_found`: the client's
 * action is identical, and describing which one it was would tell a caller
 * whether a post id they guessed exists.
 */
async function requireVisiblePost(database: Queryable, postId: string): Promise<void> {
  // Checked before the query, not after: a path parameter that is not a uuid
  // would reach PostgreSQL as a syntax error and leave the route answering 500
  // for a request that is simply wrong.
  if (!isUuid(postId)) throw notFound('post_not_found');

  const row = await database.queryOne<{ id: string }>(
    `SELECT p.id
       FROM posts p
       JOIN channels c ON c.id = p.channel_id
      WHERE p.id = $1
        AND p.deleted_at IS NULL
        AND c.status = 'active'`,
    [postId]
  );
  if (!row) throw notFound('post_not_found');
}
