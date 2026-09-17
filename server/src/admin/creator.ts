import { normalizeEmail } from '../env.js';
import type { Queryable } from '../db.js';
import { conflict, forbidden } from '../http/errors.js';
import type { VerifiedIdentity } from '../identity/verifier.js';
import { createChannel, type ChannelRow } from '../channels/service.js';
import {
  createSession,
  insertAdmin,
  loadAdmin,
  writeAudit,
  type AdminAccount,
  type AdminSessionResult,
} from './service.js';

/**
 * Creators who sign themselves up (§16).
 *
 * A creator is an administrator with one channel, who was not invited by anyone.
 * They sign in with a Firebase identity — Google, or an email/password account —
 * and the first thing they do is create the channel they will run. After that
 * they are an ordinary `channel_admin`, so every scope check in `routes.ts`
 * already applies to them and nothing in this file grants a permission.
 *
 * ## Why the account is created WITH the channel rather than before it
 *
 * The obvious design is to make an account on first sign-in and let it own zero
 * channels until it creates one. That design is wrong here, and the reason is an
 * invariant rather than taste: [insertAdmin] refuses a `channel_admin` with no
 * `channel_id`, because a channel administrator bound to nothing would pass
 * every ownership check by having nothing to compare against. Rather than weaken
 * that rule for a queue of channel-less accounts, the account comes into
 * existence at the same moment as its channel, in one transaction. A creator who
 * signs in and changes their mind leaves no row anywhere.
 *
 * ## Why an identity is matched by uid, and by email only when PROVEN
 *
 * The lookup is by `firebase_uid` first: that is the identity a creator signed
 * up with, and it is stable even if they change their address.
 *
 * The second lookup — matching an incoming identity onto an administrator that
 * already holds the same address — is allowed only when Firebase says the
 * address is VERIFIED. Firebase reports an address as *claimed* the moment
 * somebody types it into a sign-up form, and Firebase's uniqueness check does
 * not help, because the accounts this matters for live in `admin_users` and not
 * in Firebase: anybody could sign up holding the super administrator's address
 * without Firebase objecting. Matching on that would be an account takeover.
 *
 * A verified address is a different thing. It is Google's word that the caller
 * controls the mailbox, and this is the ordinary "sign in with Google" account
 * linking every product does. Without it the deployment's own owner could not
 * use Google sign-in with the address they configured as their super
 * administrator — they would be told their own email already runs an account,
 * which is true and useless.
 *
 * An UNVERIFIED collision is still refused with `email_taken`, and nothing is
 * linked onto the existing account.
 *
 * ## What a creator can and cannot do
 *
 * They may create exactly one channel — theirs becomes theirs in the same
 * transaction — and then publish to it. They cannot create a second, cannot mint
 * another administrator, cannot see any other channel, and cannot reach a
 * super-admin route. §17 keeps working unchanged: a super administrator lists,
 * disables and deletes these accounts through the routes that already exist.
 */

/** What a verified creator identity is paired with when it signs in. */
export interface CreatorRequest {
  readonly identity: VerifiedIdentity;
  readonly ipHash: string;
  readonly userAgent: string | null;
}

/**
 * A creator's own account row, or null.
 *
 * Read by `firebase_uid` against a partial unique index, so this is one index
 * lookup and cannot return two rows.
 */
async function findCreatorId(
  database: Queryable,
  uid: string
): Promise<{ id: string; channel_id: string | null } | null> {
  return database.queryOne<{ id: string; channel_id: string | null }>(
    `SELECT id, channel_id FROM admin_users WHERE firebase_uid = $1`,
    [uid]
  );
}

/**
 * Attach a PROVEN address to the administrator that already holds it, and
 * return that row.
 *
 * Null in every other case, which leaves the caller on the `needs_channel` path
 * — including the one where the address is already taken but unproven, whose
 * refusal then happens at channel creation with a message that says so.
 *
 * The firebase_uid is written here rather than only at account creation, so the
 * link is durable: the NEXT sign-in finds the row by uid, which is the fast and
 * unambiguous path, and the address no longer has to be re-proven.
 *
 * A super administrator matches this too, and should: the deployment provisions
 * that account from its environment, so nobody ever chose a password they could
 * type into the app, and Google is the only way its owner can sign in with an
 * account they actually control. It is returned with its own role, so §17's
 * control is reached rather than a second, parallel account being made.
 */
async function linkVerifiedEmail(
  database: Queryable,
  identity: VerifiedIdentity
): Promise<{ id: string; channel_id: string | null } | null> {
  const email = creatorEmail(identity);
  if (!email || !identity.emailVerified) return null;

  const target = await database.queryOne<{ id: string; channel_id: string | null; firebase_uid: string | null }>(
    `SELECT id, channel_id, firebase_uid FROM admin_users WHERE email_normalized = $1`,
    [normalizeEmail(email)]
  );

  if (!target) return null;

  // Already bound to a different Firebase identity. Refused rather than
  // overwritten: moving an account between identities is a thing a super
  // administrator should decide deliberately, not something a sign-in does.
  if (target.firebase_uid && target.firebase_uid !== identity.uid) {
    await writeAudit(database, {
      adminId: target.id,
      adminEmail: email,
      actorRole: null,
      action: 'admin.link_refused',
      targetType: 'admin',
      targetId: target.id,
      outcome: 'denied',
      metadata: { reason: 'already_linked' },
    });
    return null;
  }

  if (!target.firebase_uid) {
    await database.query(
      `UPDATE admin_users SET firebase_uid = $2 WHERE id = $1 AND firebase_uid IS NULL`,
      [target.id, identity.uid]
    );
    await writeAudit(database, {
      adminId: target.id,
      adminEmail: email,
      actorRole: null,
      action: 'admin.link',
      targetType: 'admin',
      targetId: target.id,
      metadata: { via: 'verified_email' },
    });
  }

  return { id: target.id, channel_id: target.channel_id };
}

/** The address a creator signed up with, or null if Firebase gave none. */
function creatorEmail(identity: VerifiedIdentity): string | null {
  const email = identity.email?.trim();
  return email ? email : null;
}

export type CreatorSignInResult =
  | { readonly kind: 'session'; readonly session: AdminSessionResult }
  | { readonly kind: 'needs_channel'; readonly email: string | null };

/**
 * Sign a creator in, or tell them they have no channel yet.
 *
 * `needs_channel` is not an error: it is the state every creator is in exactly
 * once, and the app answers it by opening the create-a-channel form rather than
 * by showing a failure. It carries no session, because there is nothing an
 * account-less caller may do.
 *
 * An anonymous reader's token is refused here. §3's sign-in is available to
 * every install of the app, so treating it as a creator identity would make
 * self-service channel creation available to anything that can call `/api/v1`.
 */
export async function signInCreator(
  database: Queryable,
  request: CreatorRequest
): Promise<CreatorSignInResult> {
  const uid = request.identity.uid;

  // The identity that signed up before, and — when their address is proven —
  // an administrator row that was provisioned with the same one.
  const found = await findCreatorId(database, uid) ?? await linkVerifiedEmail(database, request.identity);
  if (!found) {
    return { kind: 'needs_channel', email: creatorEmail(request.identity) };
  }

  const account = await loadAdmin(database, found.id);
  if (!account) {
    // The row was there a statement ago and is gone now — a deletion raced this
    // sign-in. Treated exactly as "no channel yet", which is what the caller
    // would see on the next attempt anyway.
    return { kind: 'needs_channel', email: creatorEmail(request.identity) };
  }
  if (account.status !== 'active') {
    await writeAudit(database, {
      adminId: account.id,
      adminEmail: account.email,
      actorRole: account.role,
      action: 'admin.login',
      outcome: 'denied',
      metadata: { reason: 'disabled', via: 'firebase' },
      ipHash: request.ipHash,
    });
    throw forbidden('admin_disabled', 'This creator account is disabled.');
  }

  const session = await createSession(database, account, {
    ipHash: request.ipHash,
    userAgent: request.userAgent,
  });

  await writeAudit(database, {
    adminId: account.id,
    adminEmail: account.email,
    actorRole: account.role,
    action: 'admin.login',
    targetType: 'admin',
    targetId: account.id,
    metadata: { role: account.role, via: 'firebase' },
    ipHash: request.ipHash,
  });

  return { kind: 'session', session };
}

export interface CreateCreatorChannelInput extends CreatorRequest {
  readonly name: string;
  readonly description?: string;
  readonly categorySlug?: string;
  readonly countryCode?: string;
}

/**
 * Create a creator's channel and their account for it, and sign them in.
 *
 * One transaction, because the two rows are one fact: an account with no channel
 * is unreachable (see the note at the top of this file) and a channel with no
 * administrator is one nobody can publish to. A partial commit here would be a
 * channel stranded without its owner, which is exactly the state the super
 * admin's delete-channel path exists to clean up and would have to be pointed at
 * by hand.
 *
 * A creator may do this ONCE. The second attempt finds their existing account
 * and is refused with `channel_exists`, which is checked inside the transaction
 * so two simultaneous submissions cannot both succeed.
 */
export async function createCreatorChannel(
  database: Queryable,
  input: CreateCreatorChannelInput
): Promise<{ readonly session: AdminSessionResult; readonly channel: ChannelRow }> {
  const uid = input.identity.uid;
  const email = creatorEmail(input.identity);

  if (!email) {
    // A provider that issues no email cannot own an account: the column is NOT
    // NULL and the address is what a super admin sees in the administrators
    // list. Only reachable if a provider is enabled that this product does not
    // expect, so it is refused with a sentence rather than a constraint error.
    throw conflict(
      'email_required',
      'This sign-in method did not provide an email address. Use Google or an email and password.'
    );
  }

  const result = await database.transaction(async (tx) => {
    const existing = await findCreatorId(tx, uid);
    if (existing) {
      throw conflict('channel_exists', 'This account already runs a channel.');
    }

    const channel = await createChannel(tx, {
      name: input.name,
      description: input.description,
      categorySlug: input.categorySlug,
      countryCode: input.countryCode,
    });

    // `passwordHash: null` — a creator has no password. Nothing can sign this
    // account in through the password form, and the login path refuses it by
    // comparing against a dummy hash rather than by special-casing a role.
    const admin = await insertAdmin(tx, {
      displayName: email.split('@')[0] || channel.name,
      email,
      role: 'channel_admin',
      channelId: channel.id,
      createdByAdminId: null,
      passwordHash: null,
      firebaseUid: uid,
    });

    return { channel, admin };
  });

  const session = await createSession(database, result.admin, {
    ipHash: input.ipHash,
    userAgent: input.userAgent,
  });

  await writeAudit(database, {
    adminId: result.admin.id,
    adminEmail: result.admin.email,
    actorRole: result.admin.role,
    action: 'channel.create',
    targetType: 'channel',
    targetId: result.channel.id,
    metadata: { slug: result.channel.slug, via: 'firebase' },
    ipHash: input.ipHash,
  });

  return { session, channel: result.channel };
}

/** The account a creator identity owns, for the sign-in response. */
export async function creatorAccount(
  database: Queryable,
  uid: string
): Promise<AdminAccount | null> {
  const found = await findCreatorId(database, uid);
  return found ? loadAdmin(database, found.id) : null;
}
