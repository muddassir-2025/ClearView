/**
 * The platform administration role and permission model (§17, §18, §25).
 *
 * Two roles, and the distinction between them is the whole of §18:
 *
 *  * **super_admin** — every channel, every post, every administrator. The only
 *    account that may create a channel together with the login that runs it.
 *
 *  * **channel_admin** — one channel, and only that channel. They may publish,
 *    edit and remove their own posts and their own channel's profile, and they
 *    may not see, touch or even learn the existence of another channel.
 *
 * Two things about this file are load-bearing.
 *
 *  * **Permissions are a compiled matrix, not a database table.** §28-style
 *    granular permissions do not ask to be editable at runtime, and a
 *    `permissions` table would need its own UI, its own migration path and a
 *    cache — while the failure mode of a mis-edited permission row is a
 *    privilege escalation, not a cosmetic bug. Adding a permission here is a
 *    one-line change reviewed like any other code, which is the property that
 *    actually keeps it safe.
 *
 *  * **The role is never taken from a request.** It is read from `admin_users`
 *    on every request, and every decision goes through [can]. The client sends
 *    no role and no permission flag that is believed.
 *
 * `channel_admin`'s rows here are deliberately narrow. Everything they are
 * missing is a capability §18 forbids outright: no `channels.create` (they do
 * not mint channels), no `admins.*` (they do not mint administrators), no
 * `channels.delete`, and no way to read another channel — which is not a
 * permission at all, because the scope check that follows every lookup is what
 * makes "own channel only" true.
 */

export type AdminRole = 'super_admin' | 'channel_admin';

export const ADMIN_ROLES: readonly AdminRole[] = ['super_admin', 'channel_admin'];

export type AdminStatus = 'active' | 'disabled';

/**
 * Every distinct thing an administrator can attempt.
 *
 * Named `<resource>.<verb>` so a denial logs something an operator can read and
 * a new action has an obvious place to live. Coarse enough to be reviewable: a
 * permission per endpoint would be a permission nobody audits.
 */
export type AdminAction =
  | 'channels.read'
  | 'channels.create'
  | 'channels.update'
  | 'channels.status'
  | 'channels.delete'
  | 'posts.read'
  | 'posts.create'
  | 'posts.update'
  | 'posts.delete'
  | 'media.upload'
  | 'admins.read'
  | 'admins.manage'
  | 'audit.read';

/**
 * Who may do what.
 *
 * Read as a table rather than derived from a role ranking. The two roles here
 * happen to nest, but a `>=` comparison would silently grant the wrong set the
 * first time a role-specific capability is added — which is exactly what §18
 * requires not to happen, since a channel admin gaining one capability that a
 * super admin holds is one capability too many.
 */
const GRANTS: Readonly<Record<AdminRole, readonly AdminAction[]>> = {
  /** §18: their own channel's content, and nothing else. */
  channel_admin: [
    'channels.read',
    'channels.update',
    'posts.read',
    'posts.create',
    'posts.update',
    'posts.delete',
    'media.upload',
  ],

  /** §17: everything. */
  super_admin: [
    'channels.read',
    'channels.create',
    'channels.update',
    'channels.status',
    'channels.delete',
    'posts.read',
    'posts.create',
    'posts.update',
    'posts.delete',
    'media.upload',
    'admins.read',
    'admins.manage',
    'audit.read',
  ],
};

/**
 * Whether this role may attempt this action.
 *
 * Takes a `role` and not an administrator row: the caller has already loaded and
 * verified the account, and keeping this a pure function is what makes the whole
 * matrix testable without a database.
 *
 * An unrecognised role is refused rather than throwing. Migration 013 disables
 * the legacy ADMIN and MODERATOR rows and constrains the column, but a
 * `can()` that crashed on an unexpected string would turn "an old row survived"
 * into a 500 on every request instead of a refusal — and a refusal is both the
 * safe answer and the honest one.
 */
export function can(role: string, action: AdminAction): boolean {
  const granted = GRANTS[role as AdminRole];
  return Array.isArray(granted) && granted.includes(action);
}

/** Every action a role holds. */
export function permissionsFor(role: AdminRole): readonly AdminAction[] {
  return GRANTS[role];
}

/** Whether a role is one this build recognises. */
export function isAdminRole(value: string): value is AdminRole {
  return ADMIN_ROLES.includes(value as AdminRole);
}

/**
 * Readable descriptions, kept beside the matrix so a new action cannot be added
 * without a label an audit line can use — the log is read by people.
 */
export const ACTION_LABELS: Readonly<Record<AdminAction, string>> = {
  'channels.read': 'View channels',
  'channels.create': 'Create a channel',
  'channels.update': 'Edit a channel’s name, description or category',
  'channels.status': 'Disable or re-enable a channel',
  'channels.delete': 'Delete a channel',
  'posts.read': 'View posts',
  'posts.create': 'Publish a post',
  'posts.update': 'Edit a post',
  'posts.delete': 'Remove a post',
  'media.upload': 'Upload images and videos',
  'admins.read': 'View administrator accounts',
  'admins.manage': 'Create, disable and re-role administrators',
  'audit.read': 'Read the audit log',
};
