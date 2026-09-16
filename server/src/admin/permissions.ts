/**
 * The platform administration role and permission model (§20, §28, §32).
 *
 * A single table, checked SERVER-SIDE on every admin request. Two decisions are
 * worth stating plainly.
 *
 *  * **Permissions are a compiled matrix, not a database table.** §28 asks for
 *    granular permissions; it does not ask for them to be editable at runtime.
 *    A `permissions` table would need its own admin UI, its own migration path,
 *    and a cache — and the failure mode of a mis-edited permission row is a
 *    privilege escalation, not a cosmetic bug. Adding a permission here is a
 *    one-line change reviewed like any other code, which is the property that
 *    actually keeps it safe.
 *
 *  * **The matrix follows §28 literally, including its asymmetries.** §28 lists
 *    "ban users" under SUPER_ADMIN and not under ADMIN, so an ADMIN may suspend
 *    an account but not permanently ban it, and may not lift a ban either. That
 *    is a deliberate reading: a ban blocks a mobile identity from ever
 *    registering again, which is not a reversible-looking action, so it takes
 *    the role that §28 says owns it.
 *
 * The client never decides any of this (§28, §32): the dashboard asks the API
 * what the signed-in administrator may do so it can hide controls, and the API
 * re-checks every action regardless of what the dashboard rendered.
 */

export type AdminRole = 'super_admin' | 'admin' | 'moderator';

export const ADMIN_ROLES: readonly AdminRole[] = ['super_admin', 'admin', 'moderator'];

export type AdminStatus = 'active' | 'disabled';

/**
 * Every distinct thing an administrator can attempt.
 *
 * Named `<resource>.<verb>` so a denial logs something an operator can read,
 * and so a new action has an obvious place to live. Each one is checked in
 * exactly one place — [can] — which is why the verbs are coarse enough to be
 * reviewable: a permission per endpoint would be a permission nobody audits.
 */
export type AdminAction =
  | 'overview.read'
  | 'users.read'
  | 'users.moderate'
  | 'users.ban'
  | 'channels.read'
  | 'channels.moderate'
  | 'posts.read'
  | 'posts.moderate'
  | 'reports.read'
  | 'reports.work'
  | 'messages.send'
  | 'conversations.read'
  | 'identities.read'
  | 'identities.lift'
  | 'admins.read'
  | 'admins.manage'
  | 'audit.read'
  | 'settings.read'
  | 'settings.write';

/**
 * Who may do what.
 *
 * Read as a table rather than derived from role ranking, because the roles are
 * NOT a strict hierarchy: an ADMIN can send official messages and a
 * SUPER_ADMIN can too, but a MODERATOR can remove a post while it cannot send
 * a platform notice. A `>=` comparison would silently grant the wrong set the
 * first time a role-specific capability was added.
 */
const GRANTS: Readonly<Record<AdminRole, readonly AdminAction[]>> = {
  /**
   * §28: "reports, posts, limited user moderation". The limit is enforced by
   * this row plus [LIMITED_ACTIONS]: a moderator may look at any user and act
   * on their sessions, but may not suspend or ban them.
   */
  moderator: [
    'overview.read',
    'users.read',
    'reports.read',
    'reports.work',
    'channels.read',
    'posts.read',
    'posts.moderate',
    'audit.read',
  ],

  /** §28: users, channels, posts, reports, official messages. */
  admin: [
    'overview.read',
    'users.read',
    'users.moderate',
    'channels.read',
    'channels.moderate',
    'posts.read',
    'posts.moderate',
    'reports.read',
    'reports.work',
    'messages.send',
    'conversations.read',
    'identities.read',
    'admins.read',
    'audit.read',
    'settings.read',
  ],

  /** §28: everything, plus admins, platform settings and bans. */
  super_admin: [
    'overview.read',
    'users.read',
    'users.moderate',
    'users.ban',
    'channels.read',
    'channels.moderate',
    'posts.read',
    'posts.moderate',
    'reports.read',
    'reports.work',
    'messages.send',
    'conversations.read',
    'identities.read',
    'identities.lift',
    'admins.read',
    'admins.manage',
    'audit.read',
    'settings.read',
    'settings.write',
  ],
};

/**
 * Whether this role may attempt this action.
 *
 * Takes a `role` and not an administrator row: the caller has already loaded
 * and verified the account, and keeping this a pure function is what makes the
 * whole matrix testable without a database.
 */
export function can(role: AdminRole, action: AdminAction): boolean {
  return GRANTS[role].includes(action);
}

/** Every action a role holds, for the dashboard's own capability list. */
export function permissionsFor(role: AdminRole): readonly AdminAction[] {
  return GRANTS[role];
}

/**
 * §28's "granular permissions rather than assuming every admin has unlimited
 * power", made checkable: is any role strictly contained in another?
 *
 * Used by the suite to prove the hierarchy is what the table says — a MODERATOR
 * must not hold an action that an ADMIN lacks, because that would mean the
 * reporting of who can do what is wrong even if every individual grant is
 * defensible.
 */
export function roleIsSubsetOf(role: AdminRole, other: AdminRole): boolean {
  return GRANTS[role].every((action) => GRANTS[other].includes(action));
}

/**
 * Readable descriptions. Kept beside the matrix so a new action cannot be added
 * without a label an audit line can use — §29's log is read by people.
 */
export const ACTION_LABELS: Readonly<Record<AdminAction, string>> = {
  'overview.read': 'View the dashboard overview',
  'users.read': 'View user accounts',
  'users.moderate': 'Suspend or reinstate a user, and revoke their sessions',
  'users.ban': 'Permanently ban a user and block their mobile identity',
  'channels.read': 'View channels',
  'channels.moderate': 'Suspend, restore or ban a channel',
  'posts.read': 'View posts',
  'posts.moderate': 'Remove or restore a post',
  'reports.read': 'View the report queue',
  'reports.work': 'Claim and resolve reports',
  'messages.send': 'Send official platform messages',
  'conversations.read': 'Read a channel’s private follower conversations',
  'identities.read': 'View banned mobile identities',
  'identities.lift': 'Lift a mobile-number ban',
  'admins.read': 'View administrator accounts',
  'admins.manage': 'Create, disable and re-role administrators',
  'audit.read': 'Read the audit log',
  'settings.read': 'View platform settings',
  'settings.write': 'Change platform settings',
};
