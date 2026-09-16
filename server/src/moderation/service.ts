import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, one, type Queryable } from '../db.js';
import { badRequest, conflict, forbidden, notFound } from '../http/errors.js';
import {
  cursorOf,
  encodeCursor,
  isUuid,
  parsePageSize,
  withLimit,
  type Page,
  type PageQuery,
} from '../channels/cursor.js';
import { roleOf as channelRoleOf, type ChannelRole } from '../channels/service.js';
import { revokeAllSessions } from '../auth/service.js';

/**
 * Moderation (§18, §19), private follower messages (§16) and user blocks (§12).
 *
 * Four rules shape this module.
 *
 *  * **A reporter never learns anything.** Report responses carry the report's
 *    own status and nothing about the target's history, other reports, or what
 *    a moderator decided. §18 wants a queue; it does not want a way to probe
 *    another account by filing reports against it.
 *
 *  * **Private messages stay private, structurally.** The payload shapes here
 *    are built field by field, and the only identity they can carry is a
 *    display name and avatar — the public profile (§38). There is no function
 *    that returns an email or a phone number, so no future caller can leak one
 *    by accident.
 *
 *  * **Moderation actions are separate from reports.** Resolving a report is a
 *    bookkeeping act; suspending an account is a state change. Keeping them
 *    apart is what makes §18's "status, moderator/admin action, resolution
 *    timestamp" three honest fields instead of one overloaded one.
 *
 *  * **A ban is a transaction.** §19 requires the status change, the identity
 *    block and the session revocation to happen together: any one alone leaves
 *    either a banned account that keeps working or a blocked number whose
 *    account is still active.
 */

export type ReportTargetType = 'user' | 'channel' | 'post' | 'message';
export type ReportReason =
  | 'spam'
  | 'abuse'
  | 'harassment'
  | 'impersonation'
  | 'misinformation'
  | 'illegal'
  | 'other';
export type ReportStatus = 'open' | 'reviewing' | 'resolved' | 'dismissed';

export const REPORT_REASONS: readonly ReportReason[] = [
  'spam',
  'abuse',
  'harassment',
  'impersonation',
  'misinformation',
  'illegal',
  'other',
];

export const REPORT_TARGET_TYPES: readonly ReportTargetType[] = [
  'user',
  'channel',
  'post',
  'message',
];

/** Roles that answer a channel's follower messages (§16). */
const RESPONDING_ROLES: readonly ChannelRole[] = ['owner', 'editor', 'responder'];

/**
 * Another user, as anyone is allowed to see them (§38).
 *
 * Four fields, none of them private. `email` and `phone_hash` are not selected
 * by the query that builds this, so they cannot appear here even by mistake —
 * which is the only form of that guarantee worth having.
 */
export interface PublicProfile {
  readonly id: string;
  readonly displayName: string;
  readonly bio: string | null;
  readonly avatarObjectKey: string | null;
}

const PUBLIC_PROFILE_COLUMNS = `u.id, u.display_name, u.bio, u.avatar_object_key`;

interface PublicProfileRow {
  id: string;
  display_name: string;
  bio: string | null;
  avatar_object_key: string | null;
}

function toPublicProfile(row: PublicProfileRow): PublicProfile {
  return {
    id: row.id,
    displayName: row.display_name,
    bio: row.bio,
    avatarObjectKey: row.avatar_object_key,
  };
}

/** Load a user as the public sees them, or throw. Deleted accounts are gone. */
export async function loadPublicProfile(
  database: Queryable,
  userId: string
): Promise<PublicProfile> {
  if (!isUuid(userId)) throw notFound('user_not_found');

  const row = await database.queryOne<PublicProfileRow>(
    `SELECT ${PUBLIC_PROFILE_COLUMNS} FROM users u WHERE u.id = $1 AND u.deleted_at IS NULL`,
    [userId]
  );
  if (!row) throw notFound('user_not_found');
  return toPublicProfile(row);
}

// ── Reports (§18) ───────────────────────────────────────────────────────

export interface CreateReportInput {
  readonly targetType: ReportTargetType;
  readonly targetId: string;
  readonly reason: ReportReason;
  readonly details?: string | undefined;
}

export interface ReportSummary {
  readonly id: string;
  readonly targetType: ReportTargetType;
  readonly reason: ReportReason;
  readonly details: string | null;
  readonly status: ReportStatus;
  readonly createdAt: string;
  readonly resolvedAt: string | null;
  /**
   * What a moderator decided, for the reporter's own record.
   *
   * Included because §18 makes the outcome part of the report and a reporter
   * who cannot see it will file again. It contains nothing about the target's
   * account state.
   */
  readonly actionTaken: string | null;
}

interface ReportRow {
  id: string;
  target_type: ReportTargetType;
  reason: ReportReason;
  details: string | null;
  status: ReportStatus;
  action_taken: string | null;
  created_at: unknown;
  resolved_at: unknown;
}

const REPORT_COLUMNS = `id, target_type, reason, details, status, action_taken, created_at, resolved_at`;

function mapReport(row: ReportRow): ReportSummary {
  return {
    id: row.id,
    targetType: row.target_type,
    reason: row.reason,
    details: row.details,
    status: row.status,
    createdAt: isoOrNull(row.created_at) ?? '',
    resolvedAt: isoOrNull(row.resolved_at),
    actionTaken: row.action_taken,
  };
}

/**
 * Confirm the target exists AND that this reporter is entitled to report it.
 *
 * The entitlement check is the interesting half. A message is the clearest
 * case: only the two sides of a conversation may report a line in it. Skipping
 * that would turn "report a message" into an oracle — file a report against a
 * guessed message id and the error code tells you whether it exists.
 *
 * Every branch returns the same `report_target_not_found` for a missing target,
 * and for a target the reporter cannot see, so neither is distinguishable.
 */
async function assertReportable(
  database: Queryable,
  reporterId: string,
  input: CreateReportInput
): Promise<void> {
  const targetId = input.targetId;
  if (!isUuid(targetId)) throw notFound('report_target_not_found');

  if (input.targetType === 'user') {
    if (targetId === reporterId) {
      throw badRequest('cannot_report_self', 'You cannot report your own account.');
    }
    const row = await database.queryOne(
      `SELECT 1 FROM users WHERE id = $1 AND deleted_at IS NULL`,
      [targetId]
    );
    if (!row) throw notFound('report_target_not_found');
    return;
  }

  if (input.targetType === 'channel') {
    const row = await database.queryOne(
      `SELECT 1 FROM channels WHERE id = $1 AND deleted_at IS NULL`,
      [targetId]
    );
    if (!row) throw notFound('report_target_not_found');
    return;
  }

  if (input.targetType === 'post') {
    const row = await database.queryOne(
      `SELECT 1 FROM posts WHERE id = $1 AND deleted_at IS NULL`,
      [targetId]
    );
    if (!row) throw notFound('report_target_not_found');
    return;
  }

  // A message. The reporter must be a side of the conversation that owns it —
  // either the follower who opened it, or an admin of its channel.
  const conversation = await database.queryOne<{ channel_id: string; follower_id: string }>(
    `SELECT c.channel_id, c.follower_id
       FROM channel_messages m
       JOIN channel_conversations c ON c.id = m.conversation_id
      WHERE m.id = $1`,
    [targetId]
  );
  if (!conversation) throw notFound('report_target_not_found');

  if (conversation.follower_id === reporterId) return;

  const role = await channelRoleOf(database, conversation.channel_id, reporterId);
  if (role === null || !RESPONDING_ROLES.includes(role)) {
    throw notFound('report_target_not_found');
  }
}

/**
 * File a report (§18).
 *
 * A duplicate is reported as `conflict/duplicate_report` rather than
 * succeeding silently: the reporter is owed the truth that their earlier report
 * is still in the queue, and the unique index is what makes that determination
 * race-free.
 */
export async function createReport(
  database: Queryable,
  reporterId: string,
  input: CreateReportInput
): Promise<ReportSummary> {
  if (!REPORT_REASONS.includes(input.reason)) {
    throw badRequest('invalid_reason', 'That is not a report reason this app offers.');
  }
  if (!REPORT_TARGET_TYPES.includes(input.targetType)) {
    throw badRequest('invalid_target_type', 'That is not something that can be reported.');
  }

  const details = input.details?.trim() ?? '';
  if (details.length > env.MAX_REPORT_DETAILS_LENGTH) {
    throw badRequest(
      'details_too_long',
      `A report can hold at most ${env.MAX_REPORT_DETAILS_LENGTH} characters of detail.`
    );
  }

  await assertReportable(database, reporterId, input);

  const columns: Record<ReportTargetType, string> = {
    user: 'target_user_id',
    channel: 'target_channel_id',
    post: 'target_post_id',
    message: 'target_message_id',
  };

  // The target column is chosen from a compiled-in table, never from the
  // request, so the only interpolated fragment is one of four fixed names.
  const existing = await database.queryOne(
    `SELECT id FROM reports WHERE reporter_id = $1 AND ${columns[input.targetType]} = $2`,
    [reporterId, input.targetId]
  );
  if (existing) {
    throw conflict('duplicate_report', 'You have already reported this.');
  }

  const rows = await database.query<ReportRow>(
    `INSERT INTO reports (reporter_id, target_type, ${columns[input.targetType]}, reason, details)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING ${REPORT_COLUMNS}`,
    [
      reporterId,
      input.targetType,
      input.targetId,
      input.reason,
      details === '' ? null : details,
    ]
  );

  return mapReport(one(rows));
}

/** The caller's own reports (§18), newest first. */
export async function listOwnReports(
  database: Queryable,
  reporterId: string
): Promise<readonly ReportSummary[]> {
  const rows = await database.query<ReportRow>(
    `SELECT ${REPORT_COLUMNS} FROM reports
      WHERE reporter_id = $1
      ORDER BY created_at DESC
      LIMIT 100`,
    [reporterId]
  );
  return rows.map(mapReport);
}

// ── User blocks (§12) ───────────────────────────────────────────────────

/**
 * Block another person.
 *
 * Idempotent, and unconditional: nothing about the other account is revealed to
 * decide whether the block is "valid" — the whole point of a block is that it
 * works without the other side's cooperation.
 */
export async function blockUser(
  database: Queryable,
  blockerId: string,
  blockedId: string
): Promise<void> {
  if (blockedId === blockerId) {
    throw badRequest('cannot_block_self', 'You cannot block yourself.');
  }
  await loadPublicProfile(database, blockedId);

  await database.query(
    `INSERT INTO user_blocks (blocker_id, blocked_id) VALUES ($1, $2)
     ON CONFLICT (blocker_id, blocked_id) DO NOTHING`,
    [blockerId, blockedId]
  );
}

export async function unblockUser(
  database: Queryable,
  blockerId: string,
  blockedId: string
): Promise<void> {
  await database.query(`DELETE FROM user_blocks WHERE blocker_id = $1 AND blocked_id = $2`, [
    blockerId,
    blockedId,
  ]);
}

/** Who the caller has blocked, as public profiles. Never the reverse list. */
export async function listBlockedUsers(
  database: Queryable,
  blockerId: string
): Promise<readonly PublicProfile[]> {
  const rows = await database.query<PublicProfileRow>(
    `SELECT ${PUBLIC_PROFILE_COLUMNS}
       FROM user_blocks b
       JOIN users u ON u.id = b.blocked_id
      WHERE b.blocker_id = $1 AND u.deleted_at IS NULL
      ORDER BY b.created_at DESC
      LIMIT 200`,
    [blockerId]
  );
  return rows.map(toPublicProfile);
}

/** True when either side has blocked the other — a conversation cannot proceed. */
async function blockedBetween(
  database: Queryable,
  firstId: string,
  secondId: string
): Promise<boolean> {
  const row = await database.queryOne(
    `SELECT 1 FROM user_blocks
      WHERE (blocker_id = $1 AND blocked_id = $2) OR (blocker_id = $2 AND blocked_id = $1)`,
    [firstId, secondId]
  );
  return row !== null;
}

// ── Private follower messages (§16) ─────────────────────────────────────

export interface ConversationSummary {
  readonly id: string;
  readonly channelId: string;
  readonly channelName: string;
  readonly channelSlug: string;
  /** The follower in this conversation, as a public profile. */
  readonly follower: PublicProfile;
  readonly lastMessageAt: string | null;
  readonly createdAt: string;
  readonly blocked: boolean;
  readonly closed: boolean;
  readonly unreadCount: number;
  readonly lastMessagePreview: string | null;
}

export interface MessageSummary {
  readonly id: string;
  readonly conversationId: string;
  readonly body: string;
  readonly fromAdmin: boolean;
  readonly createdAt: string;
  readonly readAt: string | null;
}

interface ConversationRow {
  id: string;
  channel_id: string;
  channel_name: string;
  channel_slug: string;
  follower_id: string;
  follower_display_name: string;
  follower_bio: string | null;
  follower_avatar_object_key: string | null;
  last_message_at: unknown;
  created_at: unknown;
  blocked_at: unknown;
  closed_at: unknown;
  unread_count: number;
  last_message_body: string | null;
}

const CONVERSATION_COLUMNS = `
  c.id, c.channel_id, ch.name AS channel_name, ch.slug AS channel_slug,
  c.follower_id, f.display_name AS follower_display_name, f.bio AS follower_bio,
  f.avatar_object_key AS follower_avatar_object_key,
  c.last_message_at, c.created_at, c.blocked_at, c.closed_at
`;

/**
 * Build a conversation payload.
 *
 * `viewerRole` decides which side's unread count and preview are meaningful, so
 * it is passed in rather than inferred: a channel admin and a follower see the
 * same row and almost the same payload, and the difference is exactly what a
 * single shared mapping would get wrong.
 */
function mapConversation(row: ConversationRow, viewerIsChannelSide: boolean): ConversationSummary {
  return {
    id: row.id,
    channelId: row.channel_id,
    channelName: row.channel_name,
    channelSlug: row.channel_slug,
    follower: {
      id: row.follower_id,
      displayName: row.follower_display_name,
      bio: row.follower_bio,
      avatarObjectKey: row.follower_avatar_object_key,
    },
    lastMessageAt: isoOrNull(row.last_message_at),
    createdAt: isoOrNull(row.created_at) ?? '',
    blocked: isoOrNull(row.blocked_at) !== null,
    closed: isoOrNull(row.closed_at) !== null,
    unreadCount: viewerIsChannelSide ? row.unread_count : 0,
    lastMessagePreview: row.last_message_body,
  };
}

export interface StartConversationResult {
  readonly conversationId: string;
  readonly created: boolean;
}

/**
 * Open (or find) the caller's conversation with a channel (§16).
 *
 * Refuses unless the channel has opted in. §16 makes follower messages a
 * channel setting that defaults to OFF in the schema, and a follower cannot
 * override that by asking harder — the check is here rather than in the client
 * for exactly that reason.
 */
export async function startConversation(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<StartConversationResult> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const channel = await database.queryOne<{
    id: string;
    owner_id: string;
    status: string;
    allow_follower_messages: boolean;
    deleted_at: unknown;
  }>(
    `SELECT id, owner_id, status, allow_follower_messages, deleted_at
       FROM channels WHERE id = $1`,
    [channelId]
  );
  if (!channel || channel.deleted_at !== null) throw notFound('channel_not_found');

  if (channel.status !== 'active') {
    throw forbidden('channel_unavailable', 'This channel is not currently available.');
  }
  if (channel.owner_id === userId) {
    throw badRequest('own_channel', 'A channel cannot message itself.');
  }
  if (!channel.allow_follower_messages) {
    throw forbidden(
      'messages_disabled',
      'This channel does not accept messages from followers.'
    );
  }

  const following = await database.queryOne(
    `SELECT 1 FROM channel_followers WHERE channel_id = $1 AND user_id = $2`,
    [channelId, userId]
  );
  if (!following) {
    throw forbidden('not_following', 'Follow this channel before messaging it.');
  }

  return database.transaction(async (tx) => {
    const inserted = await tx.query<{ id: string }>(
      `INSERT INTO channel_conversations (channel_id, follower_id)
       VALUES ($1, $2)
       ON CONFLICT (channel_id, follower_id) DO NOTHING
       RETURNING id`,
      [channelId, userId]
    );

    if (inserted.length > 0) return { conversationId: one(inserted).id, created: true };

    const existing = await tx.queryOne<{ id: string }>(
      `SELECT id FROM channel_conversations WHERE channel_id = $1 AND follower_id = $2`,
      [channelId, userId]
    );
    // A no-op conflict means the row already existed under the same unique
    // key, so this can only be null if the channel vanished mid-transaction.
    if (!existing) throw notFound('channel_not_found');
    return { conversationId: existing.id, created: false };
  });
}

/**
 * Load a conversation plus the caller's standing in it.
 *
 * Returns the viewer's role so the caller does not have to ask twice, and
 * refuses anyone who is neither the follower nor a channel admin — a
 * conversation between two other people must not be readable by guessing its
 * id.
 */
async function loadConversation(
  database: Queryable,
  userId: string,
  conversationId: string
): Promise<{ row: ConversationRow; viewerIsChannelSide: boolean; role: ChannelRole | null }> {
  if (!isUuid(conversationId)) throw notFound('conversation_not_found');

  const row = await database.queryOne<ConversationRow>(
    `SELECT ${CONVERSATION_COLUMNS},
            (SELECT count(*)::int FROM channel_messages m
              WHERE m.conversation_id = c.id AND m.sender_role = 'follower'
                AND m.read_at IS NULL) AS unread_count,
            (SELECT m.body FROM channel_messages m
              WHERE m.conversation_id = c.id
              ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message_body
       FROM channel_conversations c
       JOIN channels ch ON ch.id = c.channel_id
       JOIN users f ON f.id = c.follower_id
      WHERE c.id = $1`,
    [conversationId]
  );
  if (!row) throw notFound('conversation_not_found');

  const viewerIsFollower = row.follower_id === userId;
  const role = await channelRoleOf(database, row.channel_id, userId);
  const viewerIsChannelSide = !viewerIsFollower && role !== null && RESPONDING_ROLES.includes(role);

  if (!viewerIsFollower && !viewerIsChannelSide) throw notFound('conversation_not_found');

  return { row, viewerIsChannelSide, role };
}

/** The channel's inbox (§16), newest activity first. */
export async function listChannelConversations(
  database: Queryable,
  userId: string,
  channelId: string
): Promise<readonly ConversationSummary[]> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const role = await channelRoleOf(database, channelId, userId);
  if (role === null || !RESPONDING_ROLES.includes(role)) {
    throw forbidden('conversation_forbidden', 'Only a channel admin can read its messages.');
  }

  const rows = await database.query<ConversationRow>(
    `SELECT ${CONVERSATION_COLUMNS},
            (SELECT count(*)::int FROM channel_messages m
              WHERE m.conversation_id = c.id AND m.sender_role = 'follower'
                AND m.read_at IS NULL) AS unread_count,
            (SELECT m.body FROM channel_messages m
              WHERE m.conversation_id = c.id
              ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message_body
       FROM channel_conversations c
       JOIN channels ch ON ch.id = c.channel_id
       JOIN users f ON f.id = c.follower_id
      WHERE c.channel_id = $1
      ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
      LIMIT 200`,
    [channelId]
  );

  return rows.map((row) => mapConversation(row, true));
}

/** The conversations the caller opened as a follower (§16). */
export async function listOwnConversations(
  database: Queryable,
  userId: string
): Promise<readonly ConversationSummary[]> {
  const rows = await database.query<ConversationRow>(
    `SELECT ${CONVERSATION_COLUMNS},
            0 AS unread_count,
            (SELECT m.body FROM channel_messages m
              WHERE m.conversation_id = c.id
              ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message_body
       FROM channel_conversations c
       JOIN channels ch ON ch.id = c.channel_id
       JOIN users f ON f.id = c.follower_id
      WHERE c.follower_id = $1
      ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
      LIMIT 200`,
    [userId]
  );

  return rows.map((row) => mapConversation(row, false));
}

/** One conversation, as its own two sides see it. */
export async function getConversation(
  database: Queryable,
  userId: string,
  conversationId: string
): Promise<ConversationSummary> {
  const { row, viewerIsChannelSide } = await loadConversation(database, userId, conversationId);
  return mapConversation(row, viewerIsChannelSide);
}

/**
 * The messages in a conversation (§16), newest first.
 *
 * Reading marks the other side's messages as read — the same act in this
 * product, since there is no way to display a thread without the reader
 * having seen it. Distinguished from [markConversationRead] being separate
 * only so the client can also mark read without re-fetching.
 */
export async function listMessages(
  database: Queryable,
  userId: string,
  conversationId: string,
  query: PageQuery
): Promise<Page<MessageSummary>> {
  const { row, viewerIsChannelSide } = await loadConversation(database, userId, conversationId);

  const limit = parsePageSize(query.limit, env.DEFAULT_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [row.id, limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (m.created_at, m.id) < ($3::timestamptz, $4::uuid)`;
  }

  const messages = await database.query<{
    id: string;
    conversation_id: string;
    body: string;
    sender_role: 'follower' | 'admin';
    created_at: unknown;
    read_at: unknown;
  }>(
    `SELECT m.id, m.conversation_id, m.body, m.sender_role, m.created_at, m.read_at
       FROM channel_messages m
      WHERE m.conversation_id = $1
        ${keyset}
      ORDER BY m.created_at DESC, m.id DESC
      LIMIT $2`,
    params
  );

  const { items, hasMore } = withLimit(messages, limit);
  const last = items[items.length - 1];

  await markConversationRead(database, userId, conversationId, viewerIsChannelSide);

  return {
    items: items.map((message) => ({
      id: message.id,
      conversationId: message.conversation_id,
      body: message.body,
      fromAdmin: message.sender_role === 'admin',
      createdAt: isoOrNull(message.created_at) ?? '',
      readAt: isoOrNull(message.read_at),
    })),
    nextCursor: hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

/** Mark the other side's messages read. Never touches the reader's own. */
export async function markConversationRead(
  database: Queryable,
  userId: string,
  conversationId: string,
  viewerIsChannelSide?: boolean
): Promise<void> {
  const resolved = viewerIsChannelSide ?? (await loadConversation(database, userId, conversationId))
    .viewerIsChannelSide;

  // The reader marks the OTHER side's messages: an admin reading marks the
  // follower's lines, and vice versa. Marking one's own would make every thread
  // look answered.
  await database.query(
    `UPDATE channel_messages
        SET read_at = now()
      WHERE conversation_id = $1 AND read_at IS NULL
        AND sender_role = $2`,
    [conversationId, resolved ? 'follower' : 'admin']
  );
}

/**
 * Send a message (§16).
 *
 * The follower's permission is re-checked on every send rather than only when
 * the conversation was created: a channel that switches messages off, or blocks
 * this follower, must take effect immediately on the next message.
 */
export async function sendMessage(
  database: Queryable,
  userId: string,
  conversationId: string,
  body: string
): Promise<MessageSummary> {
  const text = body.trim();
  if (text.length === 0 || text.length > 4000) {
    throw badRequest('invalid_message', 'A message must be between 1 and 4000 characters.');
  }

  const { row, viewerIsChannelSide } = await loadConversation(database, userId, conversationId);

  if (isoOrNull(row.closed_at) !== null) {
    throw forbidden('conversation_closed', 'This conversation is closed.');
  }

  if (viewerIsChannelSide) {
    // The channel blocked this follower: the admin must unblock before the
    // thread accepts a reply, otherwise "block" would only silence one side.
    if (isoOrNull(row.blocked_at) !== null) {
      throw forbidden('conversation_blocked', 'Unblock this conversation before replying.');
    }
  } else {
    if (isoOrNull(row.blocked_at) !== null) {
      throw forbidden('conversation_blocked', 'This channel has stopped this conversation.');
    }

    const channel = await database.queryOne<{
      status: string;
      allow_follower_messages: boolean;
      owner_id: string;
    }>(
      `SELECT status, allow_follower_messages, owner_id FROM channels WHERE id = $1`,
      [row.channel_id]
    );
    if (!channel || channel.status !== 'active' || !channel.allow_follower_messages) {
      throw forbidden(
        'messages_disabled',
        'This channel does not accept messages from followers.'
      );
    }

    const channelAdminIds = await database.query<{ user_id: string }>(
      `SELECT user_id FROM channel_admins WHERE channel_id = $1`,
      [row.channel_id]
    );
    for (const admin of channelAdminIds) {
      if (await blockedBetween(database, userId, admin.user_id)) {
        throw forbidden('conversation_blocked', 'This conversation is not available.');
      }
    }
  }

  const inserted = await database.transaction(async (tx) => {
    const message = await tx.query<{
      id: string;
      conversation_id: string;
      body: string;
      sender_role: 'follower' | 'admin';
      created_at: unknown;
      read_at: unknown;
    }>(
      `INSERT INTO channel_messages (conversation_id, sender_id, sender_role, body)
       VALUES ($1, $2, $3, $4)
       RETURNING id, conversation_id, body, sender_role, created_at, read_at`,
      [row.id, userId, viewerIsChannelSide ? 'admin' : 'follower', text]
    );

    // Same transaction as the message, so the inbox order can never disagree
    // with the messages it is ordering.
    await tx.query(`UPDATE channel_conversations SET last_message_at = now() WHERE id = $1`, [
      row.id,
    ]);

    return one(message);
  });

  return {
    id: inserted.id,
    conversationId: inserted.conversation_id,
    body: inserted.body,
    fromAdmin: inserted.sender_role === 'admin',
    createdAt: isoOrNull(inserted.created_at) ?? '',
    readAt: isoOrNull(inserted.read_at),
  };
}

/**
 * Block or unblock a conversation (§16's "manage the conversation").
 *
 * Admin-side only. A follower "blocking" a channel is `blockChannel`, which is
 * a different act with a different effect, and conflating them would let a
 * follower silently stop a channel they still follow from reaching them.
 */
export async function setConversationBlocked(
  database: Queryable,
  userId: string,
  conversationId: string,
  blocked: boolean
): Promise<ConversationSummary> {
  const { row, viewerIsChannelSide } = await loadConversation(database, userId, conversationId);
  if (!viewerIsChannelSide) {
    throw forbidden('conversation_forbidden', 'Only a channel admin can manage its messages.');
  }

  await database.query(
    `UPDATE channel_conversations
        SET blocked_at = CASE WHEN $2 THEN now() ELSE NULL END,
            blocked_by_user_id = CASE WHEN $2 THEN $3::uuid ELSE NULL END
      WHERE id = $1`,
    [row.id, blocked, blocked ? userId : null]
  );

  return getConversation(database, userId, conversationId);
}

/**
 * The user-facing notice inbox (§26).
 *
 * Lives here rather than beside the admin write path because it is a USER
 * surface: the recipient is an ordinary Good Post account, and their list of
 * notices is theirs alone. Keeping it out of `admin/query.ts` also keeps the
 * user API from pulling the administrator service (and bcrypt) into its import
 * graph for a feature that has nothing to do with signing in.
 *
 * A notice reaches a user directly OR reaches every admin of a channel — which
 * is why read state is a row per reader rather than a column on the message.
 */
export interface NoticeSummary {
  readonly id: string;
  readonly subject: string;
  readonly body: string;
  readonly createdAt: string;
  readonly readAt: string | null;
  /** Set when the notice is about a channel the reader administers. */
  readonly aboutChannelId: string | null;
}

export async function listNoticesForUser(
  database: Queryable,
  userId: string
): Promise<readonly NoticeSummary[]> {
  const rows = await database.query<{
    id: string;
    subject: string;
    body: string;
    created_at: unknown;
    read_at: unknown;
    target_channel_id: string | null;
  }>(
    `SELECT m.id, m.subject, m.body, m.created_at, r.read_at, m.target_channel_id
       FROM admin_messages m
       LEFT JOIN admin_message_reads r ON r.admin_message_id = m.id AND r.user_id = $1
      WHERE m.target_user_id = $1
         OR m.target_channel_id IN (
              SELECT channel_id FROM channel_admins WHERE user_id = $1
            )
      ORDER BY m.created_at DESC
      LIMIT 100`,
    [userId]
  );

  return rows.map((row) => ({
    id: row.id,
    subject: row.subject,
    body: row.body,
    createdAt: isoOrNull(row.created_at) ?? '',
    readAt: isoOrNull(row.read_at),
    aboutChannelId: row.target_channel_id,
  }));
}

/** Mark notices read, idempotently, one row per (notice, reader). */
export async function markNoticesRead(
  database: Queryable,
  userId: string,
  messageIds: readonly string[]
): Promise<number> {
  const ids = messageIds.filter((id) => isUuid(id));
  if (ids.length === 0) return 0;

  const rows = await database.query(
    `INSERT INTO admin_message_reads (admin_message_id, user_id)
     SELECT id, $2 FROM admin_messages WHERE id = ANY($1::uuid[])
     ON CONFLICT (admin_message_id, user_id) DO NOTHING
     RETURNING admin_message_id`,
    [ids, userId]
  );
  return rows.length;
}

// ── Moderation state changes (§18, §19) ─────────────────────────────────

export type AccountStatus = 'active' | 'suspended' | 'banned';
export type ChannelStatus = 'active' | 'suspended' | 'banned';

export interface StatusChangeResult {
  readonly status: AccountStatus | ChannelStatus;
  readonly sessionsRevoked: number;
}

/**
 * Suspend or reinstate an account (§18), and ban it (§19).
 *
 * A ban is three writes that MUST travel together:
 *
 *  1. `users.status = 'banned'` — what every request already re-reads, so the
 *     account stops working on its next call rather than when a token expires.
 *  2. A `banned_identities` row keyed on `phone_hash` — the abuse identity, which
 *     survives re-registration, a new email and a reinstalled app (§19).
 *  3. Revocation of every live session, in the same transaction.
 *
 * The email is stored on the ban row as a SECONDARY signal only. 001's note is
 * explicit that it must never be the sole basis for enforcement, because it is
 * trivially changed — so it is used for an administrator's search, never for a
 * registration check.
 *
 * Reinstating lifts the identity row rather than deleting it: the history of
 * "this number was banned" is what makes a repeat offence visible, and §29's
 * audit trail refers to it.
 */
export async function setAccountStatus(
  database: Queryable,
  userId: string,
  status: AccountStatus,
  actor: { readonly adminId: string; readonly reason: string; readonly note?: string | undefined }
): Promise<StatusChangeResult> {
  if (!isUuid(userId)) throw notFound('user_not_found');

  const account = await database.queryOne<{
    id: string;
    phone_hash: string;
    phone_hash_version: number;
    email_normalized: string;
  }>(
    `SELECT id, phone_hash, phone_hash_version, email_normalized
       FROM users WHERE id = $1 AND deleted_at IS NULL`,
    [userId]
  );
  if (!account) throw notFound('user_not_found');

  return database.transaction(async (tx) => {
    // The two timestamps get their own boolean parameters rather than a CASE on
    // `$2`: comparing one parameter both as an enum and as text makes Postgres
    // unable to deduce a single type for it ("inconsistent types deduced for
    // parameter"), and passing the booleans keeps the comparison out of SQL
    // entirely.
    await tx.query(
      `UPDATE users
          SET status = $2::account_status,
              suspended_at = CASE WHEN $3::boolean THEN now() ELSE suspended_at END,
              banned_at = CASE WHEN $4::boolean THEN now() ELSE banned_at END
        WHERE id = $1`,
      [userId, status, status === 'suspended', status === 'banned']
    );

    let sessionsRevoked = 0;

    if (status === 'banned') {
      // Idempotent: re-banning an account does not add a second identity row,
      // and must not resurrect a lifted one silently — hence the guard below.
      const held = await tx.queryOne(
        `SELECT id FROM banned_identities
          WHERE phone_hash = $1 AND lifted_at IS NULL`,
        [account.phone_hash]
      );
      if (!held) {
        await tx.query(
          `INSERT INTO banned_identities
             (phone_hash, phone_hash_version, email_normalized, reason, note, banned_by_admin_id)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [
            account.phone_hash,
            account.phone_hash_version,
            account.email_normalized,
            actor.reason,
            actor.note ?? null,
            actor.adminId,
          ]
        );
      }

      sessionsRevoked = await revokeAllSessions(tx, userId, 'account_banned');
    } else if (status === 'suspended') {
      // Suspension is not a ban, but it does end the sessions: §19's reasoning
      // applies to any state the account cannot act from, and `requireAuth`
      // would otherwise keep rejecting requests from a session the client
      // believes is fine.
      sessionsRevoked = await revokeAllSessions(tx, userId, 'account_suspended');
    } else {
      // Reinstated. The identity block is lifted so the number can register
      // again, and sessions are left alone: the account was made usable, and
      // revoking here would only force a re-login.
      await tx.query(
        `UPDATE banned_identities SET lifted_at = now()
          WHERE phone_hash = $1 AND lifted_at IS NULL`,
        [account.phone_hash]
      );
    }

    return { status, sessionsRevoked };
  });
}

/**
 * Suspend, ban or restore a channel (§18, §24).
 *
 * Independent of its owner's account on purpose (§7): suspending a channel must
 * not require banning a person, and banning a person must not erase a channel's
 * history for the people who read it.
 */
export async function setChannelStatus(
  database: Queryable,
  channelId: string,
  status: ChannelStatus,
  reason?: string | undefined
): Promise<{ status: ChannelStatus }> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const updated = await database.query<{ status: ChannelStatus }>(
    `UPDATE channels SET status = $2 WHERE id = $1 AND deleted_at IS NULL RETURNING status`,
    [channelId, status]
  );
  if (updated.length === 0) throw notFound('channel_not_found');

  // The reason is recorded on the channel's most recent reports rather than on
  // the channel row itself: a single `suspension_reason` column would be
  // overwritten by the next action and would lose the history §18 wants.
  if (reason !== undefined && reason.trim() !== '') {
    await database.query(
      `UPDATE reports
          SET action_taken = $2
        WHERE target_channel_id = $1 AND status IN ('open', 'reviewing')`,
      [channelId, reason.trim()]
    );
  }

  return { status: one(updated).status };
}
