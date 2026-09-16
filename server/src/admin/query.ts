import { env } from '../env.js';
import { cursorKeyOf, isoOrNull, type Queryable } from '../db.js';
import { notFound } from '../http/errors.js';
import { cursorOf, encodeCursor, isUuid, parsePageSize, withLimit, type Page, type PageQuery } from '../channels/cursor.js';
import { auditFailureCount } from './service.js';

/**
 * The read side of the administration dashboard (§22–§26, §29).
 *
 * Kept apart from `admin/service.ts`, which owns identity and the audit log,
 * because these are the queries an operator's screen runs and nothing else
 * calls them. Two rules apply throughout.
 *
 *  * **Only what administration requires.** §23 and §38 both say the minimum:
 *    a user row here carries an email because that is how a moderator
 *    identifies an account in a report, and it carries NO mobile number,
 *    because the number does not exist in this database in a recoverable form.
 *    An administrator can see THAT a number is on file and whether it is
 *    banned — never the number itself. That is a property of the schema, not of
 *    this module's care.
 *
 *  * **Nothing here decides permissions.** Every query assumes its caller has
 *    already been checked by `requireAdmin(action)`; keeping the check at the
 *    route means a query cannot be "safe by accident" in one place and unsafe
 *    in another.
 */

/** How many rows a dashboard list returns at most. */
const ADMIN_PAGE_SIZE = 50;

export interface OverviewCounts {
  readonly users: number;
  readonly usersBanned: number;
  readonly usersSuspended: number;
  readonly channels: number;
  readonly channelsSuspended: number;
  readonly posts: number;
  readonly postsRemoved: number;
  readonly openReports: number;
  readonly oldestOpenReportAt: string | null;
  readonly bannedIdentities: number;
  readonly admins: number;
  readonly liveAdminSessions: number;
  readonly liveUserSessions: number;
  readonly auditRows24h: number;
  /** Non-zero means the audit log failed to record something. See [writeAudit]. */
  readonly auditWriteFailures: number;
}

/**
 * The dashboard's headline numbers (§22).
 *
 * `oldestOpenReportAt` is included on purpose: a count of open reports says
 * nothing about whether anyone is working them, while the age of the oldest one
 * is the single number that shows a queue being ignored.
 */
export async function overview(database: Queryable): Promise<OverviewCounts> {
  const row = await database.queryOne<{
    users: number;
    users_banned: number;
    users_suspended: number;
    channels: number;
    channels_suspended: number;
    posts: number;
    posts_removed: number;
    open_reports: number;
    oldest_open_report_at: unknown;
    banned_identities: number;
    admins: number;
    live_admin_sessions: number;
    live_user_sessions: number;
    audit_rows_24h: number;
  }>(
    `SELECT
       (SELECT count(*)::int FROM users WHERE deleted_at IS NULL) AS users,
       (SELECT count(*)::int FROM users WHERE status = 'banned') AS users_banned,
       (SELECT count(*)::int FROM users WHERE status = 'suspended') AS users_suspended,
       (SELECT count(*)::int FROM channels WHERE deleted_at IS NULL) AS channels,
       (SELECT count(*)::int FROM channels WHERE status = 'suspended') AS channels_suspended,
       (SELECT count(*)::int FROM posts) AS posts,
       (SELECT count(*)::int FROM posts WHERE deleted_at IS NOT NULL) AS posts_removed,
       (SELECT count(*)::int FROM reports WHERE status IN ('open', 'reviewing')) AS open_reports,
       (SELECT min(created_at) FROM reports WHERE status = 'open') AS oldest_open_report_at,
       (SELECT count(*)::int FROM banned_identities WHERE lifted_at IS NULL) AS banned_identities,
       (SELECT count(*)::int FROM admin_users) AS admins,
       (SELECT count(*)::int FROM admin_sessions WHERE revoked_at IS NULL AND expires_at > now())
         AS live_admin_sessions,
       (SELECT count(*)::int FROM user_sessions WHERE revoked_at IS NULL AND expires_at > now())
         AS live_user_sessions,
       (SELECT count(*)::int FROM admin_audit_logs WHERE created_at > now() - interval '24 hours')
         AS audit_rows_24h`,
    []
  );

  return {
    users: row?.users ?? 0,
    usersBanned: row?.users_banned ?? 0,
    usersSuspended: row?.users_suspended ?? 0,
    channels: row?.channels ?? 0,
    channelsSuspended: row?.channels_suspended ?? 0,
    posts: row?.posts ?? 0,
    postsRemoved: row?.posts_removed ?? 0,
    openReports: row?.open_reports ?? 0,
    oldestOpenReportAt: isoOrNull(row?.oldest_open_report_at),
    bannedIdentities: row?.banned_identities ?? 0,
    admins: row?.admins ?? 0,
    liveAdminSessions: row?.live_admin_sessions ?? 0,
    liveUserSessions: row?.live_user_sessions ?? 0,
    auditRows24h: row?.audit_rows_24h ?? 0,
    auditWriteFailures: auditFailureCount(),
  };
}

/** What an administrator sees about a user (§23). See the header note. */
export interface AdminUserRow {
  readonly id: string;
  readonly displayName: string;
  readonly email: string;
  readonly status: string;
  readonly createdAt: string;
  readonly suspendedAt: string | null;
  readonly bannedAt: string | null;
  /** True when a mobile identity is on file. The number itself is unrecoverable. */
  readonly hasMobileIdentity: boolean;
  readonly phoneBanned: boolean;
  readonly reportCount: number;
  readonly channelCount: number;
  readonly liveSessions: number;
}

const USER_COLUMNS = `
  u.id, u.display_name, u.email, u.status, u.created_at, u.suspended_at, u.banned_at,
  (u.phone_hash IS NOT NULL) AS has_mobile_identity,
  (b.id IS NOT NULL) AS phone_banned,
  (SELECT count(*)::int FROM reports r WHERE r.target_user_id = u.id) AS report_count,
  (SELECT count(*)::int FROM channel_admins ca
    WHERE ca.user_id = u.id AND ca.role = 'owner') AS channel_count,
  (SELECT count(*)::int FROM user_sessions s
    WHERE s.user_id = u.id AND s.revoked_at IS NULL AND s.expires_at > now()) AS live_sessions
`;

interface AdminUserDbRow {
  id: string;
  display_name: string;
  email: string;
  status: string;
  created_at: unknown;
  suspended_at: unknown;
  banned_at: unknown;
  has_mobile_identity: boolean;
  phone_banned: boolean;
  report_count: number;
  channel_count: number;
  live_sessions: number;
}

function mapAdminUser(row: AdminUserDbRow): AdminUserRow {
  return {
    id: row.id,
    displayName: row.display_name,
    email: row.email,
    status: row.status,
    createdAt: isoOrNull(row.created_at) ?? '',
    suspendedAt: isoOrNull(row.suspended_at),
    bannedAt: isoOrNull(row.banned_at),
    hasMobileIdentity: row.has_mobile_identity,
    phoneBanned: row.phone_banned,
    reportCount: row.report_count,
    channelCount: row.channel_count,
    liveSessions: row.live_sessions,
  };
}

export interface UserSearchQuery extends PageQuery {
  readonly q?: string | undefined;
  readonly status?: string | undefined;
}

/**
 * Search accounts (§23).
 *
 * The search term is bound and its `%`/`_` escaped, so a query containing them
 * searches for those characters instead of silently becoming a wildcard — the
 * same rule discovery follows. `email` is searchable because a moderator
 * usually arrives with one from a support message.
 */
export async function searchUsers(
  database: Queryable,
  query: UserSearchQuery
): Promise<Page<AdminUserRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  const conditions = ['u.deleted_at IS NULL'];

  if (query.q) {
    const term = `%${query.q.trim().replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
    params.push(term);
    conditions.push(
      `(u.display_name ILIKE $${params.length} OR u.email ILIKE $${params.length})`
    );
  }
  if (query.status && ['active', 'suspended', 'banned'].includes(query.status)) {
    params.push(query.status);
    conditions.push(`u.status = $${params.length}::account_status`);
  }

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (u.created_at, u.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<AdminUserDbRow>(
    `SELECT ${USER_COLUMNS}
       FROM users u
       LEFT JOIN banned_identities b ON b.phone_hash = u.phone_hash AND b.lifted_at IS NULL
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY u.created_at DESC, u.id DESC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map(mapAdminUser),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

export interface AdminUserDetail {
  readonly user: AdminUserRow;
  readonly sessions: readonly {
    id: string;
    deviceLabel: string | null;
    createdAt: string;
    lastUsedAt: string | null;
    expiresAt: string;
    revokedAt: string | null;
    revokedReason: string | null;
  }[];
  readonly ownedChannels: readonly {
    id: string;
    slug: string;
    name: string;
    status: string;
    followerCount: number;
    createdAt: string;
  }[];
  readonly reportsAgainst: readonly {
    id: string;
    targetType: string;
    reason: string;
    status: string;
    createdAt: string;
  }[];
  readonly bans: readonly {
    id: string;
    reason: string;
    note: string | null;
    createdAt: string;
    liftedAt: string | null;
  }[];
}

/** Everything about one account that administration legitimately needs (§23). */
export async function getUserDetail(
  database: Queryable,
  userId: string
): Promise<AdminUserDetail> {
  if (!isUuid(userId)) throw notFound('user_not_found');

  const row = await database.queryOne<AdminUserDbRow>(
    `SELECT ${USER_COLUMNS}
       FROM users u
       LEFT JOIN banned_identities b ON b.phone_hash = u.phone_hash AND b.lifted_at IS NULL
      WHERE u.id = $1`,
    [userId]
  );
  if (!row) throw notFound('user_not_found');

  const [sessions, channels, reports, bans] = await Promise.all([
    database.query<{
      id: string;
      device_label: string | null;
      created_at: unknown;
      last_used_at: unknown;
      expires_at: unknown;
      revoked_at: unknown;
      revoked_reason: string | null;
    }>(
      `SELECT id, device_label, created_at, last_used_at, expires_at, revoked_at, revoked_reason
         FROM user_sessions WHERE user_id = $1
        ORDER BY created_at DESC LIMIT 50`,
      [userId]
    ),
    database.query<{
      id: string;
      slug: string;
      name: string;
      status: string;
      follower_count: number;
      created_at: unknown;
    }>(
      `SELECT c.id, c.slug, c.name, c.status, c.follower_count, c.created_at
         FROM channel_admins ca JOIN channels c ON c.id = ca.channel_id
        WHERE ca.user_id = $1
        ORDER BY c.created_at DESC LIMIT 50`,
      [userId]
    ),
    database.query<{
      id: string;
      target_type: string;
      reason: string;
      status: string;
      created_at: unknown;
    }>(
      `SELECT id, target_type, reason, status, created_at
         FROM reports WHERE target_user_id = $1
        ORDER BY created_at DESC LIMIT 50`,
      [userId]
    ),
    database.query<{
      id: string;
      reason: string;
      note: string | null;
      created_at: unknown;
      lifted_at: unknown;
    }>(
      `SELECT b.id, b.reason, b.note, b.created_at, b.lifted_at
         FROM banned_identities b
         JOIN users u ON u.phone_hash = b.phone_hash
        WHERE u.id = $1
        ORDER BY b.created_at DESC LIMIT 20`,
      [userId]
    ),
  ]);

  return {
    user: mapAdminUser(row),
    sessions: sessions.map((s) => ({
      id: s.id,
      deviceLabel: s.device_label,
      createdAt: isoOrNull(s.created_at) ?? '',
      lastUsedAt: isoOrNull(s.last_used_at),
      expiresAt: isoOrNull(s.expires_at) ?? '',
      revokedAt: isoOrNull(s.revoked_at),
      revokedReason: s.revoked_reason,
    })),
    ownedChannels: channels.map((c) => ({
      id: c.id,
      slug: c.slug,
      name: c.name,
      status: c.status,
      followerCount: c.follower_count,
      createdAt: isoOrNull(c.created_at) ?? '',
    })),
    reportsAgainst: reports.map((r) => ({
      id: r.id,
      targetType: r.target_type,
      reason: r.reason,
      status: r.status,
      createdAt: isoOrNull(r.created_at) ?? '',
    })),
    bans: bans.map((b) => ({
      id: b.id,
      reason: b.reason,
      note: b.note,
      createdAt: isoOrNull(b.created_at) ?? '',
      liftedAt: isoOrNull(b.lifted_at),
    })),
  };
}

/** A channel as the dashboard lists it (§24). Ownership is included here. */
export interface AdminChannelRow {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly status: string;
  readonly categorySlug: string | null;
  readonly countryCode: string | null;
  readonly followerCount: number;
  readonly postCount: number;
  readonly createdAt: string;
  readonly lastPostAt: string | null;
  readonly reportCount: number;
  readonly ownerId: string;
  readonly ownerName: string;
}

const CHANNEL_COLUMNS = `
  c.id, c.slug, c.name, c.status, c.category_slug, c.country_code, c.follower_count,
  c.post_count, c.created_at, c.last_post_at, c.owner_id,
  o.display_name AS owner_name,
  (SELECT count(*)::int FROM reports r WHERE r.target_channel_id = c.id) AS report_count
`;

interface AdminChannelDbRow {
  id: string;
  slug: string;
  name: string;
  status: string;
  category_slug: string | null;
  country_code: string | null;
  follower_count: number;
  post_count: number;
  created_at: unknown;
  last_post_at: unknown;
  owner_id: string;
  owner_name: string;
  report_count: number;
}

function mapAdminChannel(row: AdminChannelDbRow): AdminChannelRow {
  return {
    id: row.id,
    slug: row.slug,
    name: row.name,
    status: row.status,
    categorySlug: row.category_slug,
    countryCode: row.country_code,
    followerCount: row.follower_count,
    postCount: row.post_count,
    createdAt: isoOrNull(row.created_at) ?? '',
    lastPostAt: isoOrNull(row.last_post_at),
    reportCount: row.report_count,
    ownerId: row.owner_id,
    ownerName: row.owner_name,
  };
}

export interface ChannelSearchQuery extends PageQuery {
  readonly q?: string | undefined;
  readonly status?: string | undefined;
}

export async function searchChannels(
  database: Queryable,
  query: ChannelSearchQuery
): Promise<Page<AdminChannelRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  const conditions = ['c.deleted_at IS NULL'];

  if (query.q) {
    const term = `%${query.q.trim().replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
    params.push(term);
    conditions.push(`(c.name ILIKE $${params.length} OR c.slug ILIKE $${params.length})`);
  }
  if (query.status && ['active', 'suspended', 'banned'].includes(query.status)) {
    params.push(query.status);
    conditions.push(`c.status = $${params.length}::channel_status`);
  }

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (c.created_at, c.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<AdminChannelDbRow>(
    `SELECT ${CHANNEL_COLUMNS}
       FROM channels c
       JOIN users o ON o.id = c.owner_id
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY c.created_at DESC, c.id DESC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map(mapAdminChannel),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

export interface AdminChannelDetail {
  readonly channel: AdminChannelRow;
  readonly admins: readonly { userId: string; displayName: string; role: string; createdAt: string }[];
  readonly recentPosts: readonly {
    id: string;
    type: string;
    body: string | null;
    createdAt: string;
    removedAt: string | null;
    reportCount: number;
  }[];
  readonly allowFollowerMessages: boolean;
  readonly conversations: number;
}

/** A channel's detail view (§24): ownership, staff, recent content, reports. */
export async function getChannelDetail(
  database: Queryable,
  channelId: string
): Promise<AdminChannelDetail> {
  if (!isUuid(channelId)) throw notFound('channel_not_found');

  const row = await database.queryOne<AdminChannelDbRow & { allow_follower_messages: boolean }>(
    `SELECT ${CHANNEL_COLUMNS}, c.allow_follower_messages
       FROM channels c
       JOIN users o ON o.id = c.owner_id
      WHERE c.id = $1`,
    [channelId]
  );
  if (!row) throw notFound('channel_not_found');

  const [admins, posts, conversations] = await Promise.all([
    database.query<{ user_id: string; display_name: string; role: string; created_at: unknown }>(
      `SELECT ca.user_id, u.display_name, ca.role, ca.created_at
         FROM channel_admins ca JOIN users u ON u.id = ca.user_id
        WHERE ca.channel_id = $1
        ORDER BY ca.created_at ASC`,
      [channelId]
    ),
    database.query<{
      id: string;
      type: string;
      body: string | null;
      created_at: unknown;
      deleted_at: unknown;
      report_count: number;
    }>(
      `SELECT p.id, p.type, p.body, p.created_at, p.deleted_at,
              (SELECT count(*)::int FROM reports r WHERE r.target_post_id = p.id) AS report_count
         FROM posts p
        WHERE p.channel_id = $1
        ORDER BY p.created_at DESC LIMIT 50`,
      [channelId]
    ),
    database.queryOne<{ count: number }>(
      `SELECT count(*)::int AS count FROM channel_conversations WHERE channel_id = $1`,
      [channelId]
    ),
  ]);

  return {
    channel: mapAdminChannel(row),
    allowFollowerMessages: row.allow_follower_messages,
    admins: admins.map((a) => ({
      userId: a.user_id,
      displayName: a.display_name,
      role: a.role,
      createdAt: isoOrNull(a.created_at) ?? '',
    })),
    recentPosts: posts.map((p) => ({
      id: p.id,
      type: p.type,
      body: p.body,
      createdAt: isoOrNull(p.created_at) ?? '',
      removedAt: isoOrNull(p.deleted_at),
      reportCount: p.report_count,
    })),
    conversations: conversations?.count ?? 0,
  };
}

export interface AdminPostRow {
  readonly id: string;
  readonly channelId: string;
  readonly channelName: string;
  readonly type: string;
  readonly body: string | null;
  readonly createdAt: string;
  readonly removedAt: string | null;
  readonly removedReason: string | null;
  readonly reportCount: number;
  readonly mediaCount: number;
  readonly reactorCount: number;
  readonly viewerCount: number;
}

export interface PostSearchQuery extends PageQuery {
  readonly q?: string | undefined;
  readonly channelId?: string | undefined;
  readonly removed?: string | undefined;
}

export async function searchPosts(
  database: Queryable,
  query: PostSearchQuery
): Promise<Page<AdminPostRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  const conditions: string[] = [];

  if (query.q) {
    const term = `%${query.q.trim().replace(/[\\%_]/g, (m) => `\\${m}`)}%`;
    params.push(term);
    conditions.push(`COALESCE(p.body, '') ILIKE $${params.length}`);
  }
  if (query.channelId && isUuid(query.channelId)) {
    params.push(query.channelId);
    conditions.push(`p.channel_id = $${params.length}::uuid`);
  }
  // Defaults to live posts: a moderation queue that mixes removed content into
  // "everything" makes the list harder to read than the one extra parameter.
  if (query.removed === 'true') conditions.push('p.deleted_at IS NOT NULL');
  else conditions.push('p.deleted_at IS NULL');

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (p.created_at, p.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<{
    id: string;
    channel_id: string;
    channel_name: string;
    type: string;
    body: string | null;
    created_at: unknown;
    deleted_at: unknown;
    deleted_reason: string | null;
    report_count: number;
    media_count: number;
    reactor_count: number;
    viewer_count: number;
  }>(
    `SELECT p.id, p.channel_id, c.name AS channel_name, p.type, p.body, p.created_at,
            p.deleted_at, p.deleted_reason,
            (SELECT count(*)::int FROM reports r WHERE r.target_post_id = p.id) AS report_count,
            (SELECT count(*)::int FROM post_media m WHERE m.post_id = p.id) AS media_count,
            (SELECT count(*)::int FROM post_reactions x WHERE x.post_id = p.id) AS reactor_count,
            (SELECT count(*)::int FROM post_views v WHERE v.post_id = p.id) AS viewer_count
       FROM posts p
       JOIN channels c ON c.id = p.channel_id
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY p.created_at DESC, p.id DESC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map((row) => ({
      id: row.id,
      channelId: row.channel_id,
      channelName: row.channel_name,
      type: row.type,
      body: row.body,
      createdAt: isoOrNull(row.created_at) ?? '',
      removedAt: isoOrNull(row.deleted_at),
      removedReason: row.deleted_reason,
      reportCount: row.report_count,
      mediaCount: row.media_count,
      reactorCount: row.reactor_count,
      viewerCount: row.viewer_count,
    })),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

export interface AdminReportRow {
  readonly id: string;
  readonly reporterId: string;
  readonly reporterName: string;
  readonly targetType: string;
  readonly targetId: string;
  readonly reason: string;
  readonly details: string | null;
  readonly status: string;
  readonly createdAt: string;
  readonly resolvedAt: string | null;
  readonly actionTaken: string | null;
  readonly resolutionNote: string | null;
  readonly resolvedByAdminId: string | null;
}

/**
 * The moderation queue (§18, §22).
 *
 * `coalesce` over the four target columns is what the unique index uses, and it
 * is what lets the queue carry one `targetId` without a client having to know
 * which of four fields to read.
 */
export async function listReports(
  database: Queryable,
  query: PageQuery & { readonly status?: string | undefined; readonly targetType?: string | undefined }
): Promise<Page<AdminReportRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  const conditions: string[] = [];

  if (query.status && ['open', 'reviewing', 'resolved', 'dismissed'].includes(query.status)) {
    params.push(query.status);
    conditions.push(`r.status = $${params.length}::report_status`);
  } else {
    // The default is the working set, not everything ever filed.
    conditions.push(`r.status IN ('open', 'reviewing')`);
  }
  if (
    query.targetType &&
    ['user', 'channel', 'post', 'message'].includes(query.targetType)
  ) {
    params.push(query.targetType);
    conditions.push(`r.target_type = $${params.length}::report_target_type`);
  }

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (r.created_at, r.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  // Oldest first, unlike every other list here: a queue is worked from the
  // bottom, and newest-first ordering is how the oldest report gets ignored.
  const rows = await database.query<{
    id: string;
    reporter_id: string;
    reporter_name: string;
    target_type: string;
    target_id: string;
    reason: string;
    details: string | null;
    status: string;
    created_at: unknown;
    resolved_at: unknown;
    action_taken: string | null;
    resolution_note: string | null;
    resolved_by_admin_id: string | null;
  }>(
    `SELECT r.id, r.reporter_id, u.display_name AS reporter_name, r.target_type,
            COALESCE(r.target_user_id, r.target_channel_id, r.target_post_id, r.target_message_id)::text
              AS target_id,
            r.reason, r.details, r.status, r.created_at, r.resolved_at,
            r.action_taken, r.resolution_note, r.resolved_by_admin_id
       FROM reports r
       JOIN users u ON u.id = r.reporter_id
      WHERE ${conditions.join('\n        AND ')}
        ${keyset}
      ORDER BY r.created_at ASC, r.id ASC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map((row) => ({
      id: row.id,
      reporterId: row.reporter_id,
      reporterName: row.reporter_name,
      targetType: row.target_type,
      targetId: row.target_id,
      reason: row.reason,
      details: row.details,
      status: row.status,
      createdAt: isoOrNull(row.created_at) ?? '',
      resolvedAt: isoOrNull(row.resolved_at),
      actionTaken: row.action_taken,
      resolutionNote: row.resolution_note,
      resolvedByAdminId: row.resolved_by_admin_id,
    })),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

/**
 * One report by id, in exactly the queue's shape.
 *
 * Shares [listReports]' column list and mapper by construction — a second
 * SELECT with its own aliases is how the detail view and the queue start
 * disagreeing about the same row.
 */
export async function getReport(
  database: Queryable,
  reportId: string
): Promise<AdminReportRow | null> {
  if (!isUuid(reportId)) return null;

  const row = await database.queryOne<{
    id: string;
    reporter_id: string;
    reporter_name: string;
    target_type: string;
    target_id: string;
    reason: string;
    details: string | null;
    status: string;
    created_at: unknown;
    resolved_at: unknown;
    action_taken: string | null;
    resolution_note: string | null;
    resolved_by_admin_id: string | null;
  }>(
    `SELECT r.id, r.reporter_id, u.display_name AS reporter_name, r.target_type,
            COALESCE(r.target_user_id, r.target_channel_id, r.target_post_id, r.target_message_id)::text
              AS target_id,
            r.reason, r.details, r.status, r.created_at, r.resolved_at,
            r.action_taken, r.resolution_note, r.resolved_by_admin_id
       FROM reports r
       JOIN users u ON u.id = r.reporter_id
      WHERE r.id = $1`,
    [reportId]
  );
  if (!row) return null;

  return {
    id: row.id,
    reporterId: row.reporter_id,
    reporterName: row.reporter_name,
    targetType: row.target_type,
    targetId: row.target_id,
    reason: row.reason,
    details: row.details,
    status: row.status,
    createdAt: isoOrNull(row.created_at) ?? '',
    resolvedAt: isoOrNull(row.resolved_at),
    actionTaken: row.action_taken,
    resolutionNote: row.resolution_note,
    resolvedByAdminId: row.resolved_by_admin_id,
  };
}

/**
 * What a report is actually ABOUT, so a moderator does not have to open four
 * other screens to judge it (§18).
 *
 * One row per target type, returned as a single context object. It is
 * deliberately read-only and preview-sized: the moderator needs to see the
 * content, not to edit it, and editing from here would be a second write path
 * with its own authorization questions.
 */
export interface ReportContext {
  readonly targetType: string;
  readonly summary: Record<string, unknown>;
}

export async function getReportContext(
  database: Queryable,
  targetType: string,
  targetId: string
): Promise<ReportContext | null> {
  if (!isUuid(targetId)) return null;

  if (targetType === 'user') {
    const row = await database.queryOne<Record<string, unknown>>(
      `SELECT id::text, display_name, email, status, created_at::text AS created_at
         FROM users WHERE id = $1`,
      [targetId]
    );
    return row ? { targetType, summary: row } : null;
  }

  if (targetType === 'channel') {
    const row = await database.queryOne<Record<string, unknown>>(
      `SELECT id::text, slug, name, description, status, follower_count, created_at::text AS created_at
         FROM channels WHERE id = $1`,
      [targetId]
    );
    return row ? { targetType, summary: row } : null;
  }

  if (targetType === 'post') {
    const row = await database.queryOne<Record<string, unknown>>(
      `SELECT p.id::text, p.type, p.body, p.created_at::text AS created_at,
              p.deleted_at::text AS deleted_at, c.id::text AS channel_id, c.name AS channel_name
         FROM posts p JOIN channels c ON c.id = p.channel_id
        WHERE p.id = $1 AND p.deleted_at IS NULL`,
      [targetId]
    );
    return row ? { targetType, summary: row } : null;
  }

  if (targetType === 'message') {
    // §38: a moderator reviewing a reported message reads THAT message. The
    // conversation around it is not returned, so a report is not a way to read
    // somebody's private thread.
    const row = await database.queryOne<Record<string, unknown>>(
      `SELECT m.id::text, m.body, m.sender_role, m.created_at::text AS created_at,
              c.id::text AS conversation_id, c.channel_id::text AS channel_id, ch.name AS channel_name
         FROM channel_messages m
         JOIN channel_conversations c ON c.id = m.conversation_id
         JOIN channels ch ON ch.id = c.channel_id
        WHERE m.id = $1`,
      [targetId]
    );
    return row ? { targetType, summary: row } : null;
  }

  return null;
}

export interface BannedIdentityRow {
  readonly id: string;
  readonly userId: string | null;
  readonly displayName: string | null;
  readonly emailNormalized: string | null;
  readonly reason: string;
  readonly note: string | null;
  readonly bannedByAdminId: string | null;
  readonly createdAt: string;
  readonly liftedAt: string | null;
}

/**
 * Banned mobile identities (§19, §23).
 *
 * Note what is NOT here: the number. It exists only as `phone_hash`, so this
 * list identifies a ban by the account it belongs to and by the email recorded
 * at ban time. That is the privacy property §38 asks for, holding for
 * administrators too.
 */
export async function listBannedIdentities(
  database: Queryable,
  query: PageQuery & { readonly activeOnly?: boolean | undefined }
): Promise<Page<BannedIdentityRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  const conditions = [query.activeOnly === false ? 'true' : 'b.lifted_at IS NULL'];

  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (b.created_at, b.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<{
    id: string;
    user_id: string | null;
    display_name: string | null;
    email_normalized: string | null;
    reason: string;
    note: string | null;
    banned_by_admin_id: string | null;
    created_at: unknown;
    lifted_at: unknown;
  }>(
    `SELECT b.id, u.id AS user_id, u.display_name, b.email_normalized, b.reason, b.note,
            b.banned_by_admin_id, b.created_at, b.lifted_at
       FROM banned_identities b
       LEFT JOIN users u ON u.phone_hash = b.phone_hash
      WHERE ${conditions.join(' AND ')}
        ${keyset}
      ORDER BY b.created_at DESC, b.id DESC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map((row) => ({
      id: row.id,
      userId: row.user_id,
      displayName: row.display_name,
      emailNormalized: row.email_normalized,
      reason: row.reason,
      note: row.note,
      bannedByAdminId: row.banned_by_admin_id,
      createdAt: isoOrNull(row.created_at) ?? '',
      liftedAt: isoOrNull(row.lifted_at),
    })),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

export interface AdminMessageRow {
  readonly id: string;
  readonly adminId: string | null;
  readonly targetUserId: string | null;
  readonly targetChannelId: string | null;
  readonly targetName: string | null;
  readonly subject: string;
  readonly body: string;
  readonly createdAt: string;
}

/** Official platform messages (§26), newest first. */
export async function listAdminMessages(
  database: Queryable,
  query: PageQuery = {}
): Promise<Page<AdminMessageRow>> {
  const limit = parsePageSize(query.limit, ADMIN_PAGE_SIZE, env.MAX_PAGE_SIZE);
  const cursor = cursorOf(query.cursor);

  const params: unknown[] = [limit + 1];
  let keyset = '';
  if (cursor) {
    params.push(cursor.k, cursor.id);
    keyset = `AND (m.created_at, m.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`;
  }

  const rows = await database.query<{
    id: string;
    admin_id: string | null;
    target_user_id: string | null;
    target_channel_id: string | null;
    target_name: string | null;
    subject: string;
    body: string;
    created_at: unknown;
  }>(
    `SELECT m.id, m.admin_id, m.target_user_id, m.target_channel_id,
            COALESCE(u.display_name, c.name) AS target_name,
            m.subject, m.body, m.created_at
       FROM admin_messages m
       LEFT JOIN users u ON u.id = m.target_user_id
       LEFT JOIN channels c ON c.id = m.target_channel_id
      ${keyset === '' ? '' : `WHERE true ${keyset}`}
      ORDER BY m.created_at DESC, m.id DESC
      LIMIT $1`,
    params
  );

  const { items, hasMore } = withLimit(rows, limit);
  const last = items[items.length - 1];

  return {
    items: items.map((row) => ({
      id: row.id,
      adminId: row.admin_id,
      targetUserId: row.target_user_id,
      targetChannelId: row.target_channel_id,
      targetName: row.target_name,
      subject: row.subject,
      body: row.body,
      createdAt: isoOrNull(row.created_at) ?? '',
    })),
    nextCursor:
      hasMore && last ? encodeCursor({ k: cursorKeyOf(last.created_at), id: last.id }) : null,
  };
}

// The user-facing notice inbox deliberately does NOT live here — see
// `listNoticesForUser` in moderation/service.ts. It is a user surface, and
// putting it in this module would make the user API import the administrator
// service.
