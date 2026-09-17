import { X509Certificate } from 'node:crypto';
import jwt from 'jsonwebtoken';
import { serviceUnavailable, unauthorized } from '../http/errors.js';

/**
 * Who is calling (§3, §15, §16).
 *
 * The Android client signs a reader in ANONYMOUSLY with Firebase and sends the
 * resulting ID token as `Authorization: Bearer <token>`. This module is the one
 * place that decides whether such a token is genuine, and the only thing it
 * produces is a uid — no session, no password, nothing to leak.
 *
 * ## What a verified identity is, and is not
 *
 * It is proof that Firebase issued this token to somebody, and it is the key
 * reader-scoped state hangs off (§3's follow/reaction/favourite/notification
 * state). It is NOT a permission: an identity that comes back from here can
 * still only read what a reader may read and write what a reader may write.
 *
 * ## Why the tokens are verified here rather than trusted
 *
 * The uid decides whose follows, whose read position and whose messages a
 * request touches, so a client that could assert its own uid could read another
 * reader's state by spelling their uid. The token is therefore verified
 * properly — signature, issuer, audience and expiry — before it is believed.
 *
 * ## Why this does not use the Firebase Admin SDK
 *
 * The SDK is the usual answer, and it would work, but everything this product
 * needs from it is verifying an ID token, which needs only the project id —
 * a *public* value. The SDK brings a large dependency tree and a credential
 * story (a service-account file mounted into the deployment) to do something
 * that needs no secret at all. Firebase signs ID tokens with Google's rotating
 * RS256 keys, published as X.509 certificates, and `jsonwebtoken` — already a
 * dependency here — does the signature check. Only the key fetch below is ours,
 * and it is cached exactly as the SDK caches it.
 *
 * The consequence worth stating: **there is no Firebase secret in this
 * deployment.** Refusing a token is the failure mode of a missing project id,
 * not of a missing credential, so a misconfigured deployment cannot end up
 * accepting unsigned tokens.
 */

/** What a verified token tells us about the caller. */
export interface VerifiedIdentity {
  /** The Firebase uid — a reader's only identifier, anonymous or not. */
  readonly uid: string;
  /** Present only for a creator account (§16). Never for an anonymous reader. */
  readonly email: string | null;
  /**
   * True for §3's anonymous sign-in, false for §16's email/password creator.
   *
   * Callers use this to separate the two populations rather than re-deriving it
   * from the token, and it is read from Firebase's own claim rather than guessed
   * from an absent email: a future provider that issues no email would
   * otherwise be classified as anonymous.
   */
  readonly anonymous: boolean;

  /**
   * Whether Firebase has PROVEN this address belongs to the caller (§16).
   *
   * True for a Google account, and for an email/password account that has
   * confirmed its address. This is what makes it safe to attach the identity to
   * an EXISTING administrator account that already holds the same address: the
   * claim is Google's word that the caller controls the mailbox, not the
   * caller's own.
   *
   * The distinction is load-bearing rather than cosmetic. Firebase reports an
   * address as CLAIMED the moment somebody types it into a sign-up form, and
   * Firebase's own uniqueness check does not help here — the accounts this
   * matters for live in `admin_users`, not in Firebase, so somebody could sign
   * up holding the super administrator's address without Firebase objecting.
   * Matching on an unverified claim would hand them that account.
   *
   * Defaults to false when the claim is absent, so a token that does not say is
   * treated as unproven rather than as proven.
   */
  readonly emailVerified: boolean;
}

export interface IdentityVerifier {
  /**
   * Whether verification is configured at all.
   *
   * Exposed so a route can answer `auth_unavailable` (503 — the deployment is
   * incomplete, retry later) instead of `invalid_token` (401 — the caller's
   * token is bad, retrying is pointless). Those are different instructions to
   * the client, and the Android app words them differently.
   */
  readonly configured: boolean;
  verify(idToken: string): Promise<VerifiedIdentity>;
}

/**
 * Where the current signing certificates come from.
 *
 * Injectable so the suite can verify against a key pair it generated, which is
 * what lets the signature, issuer, audience and expiry checks be tested for
 * real instead of being asserted around. Production uses the default.
 */
export interface CertificateSet {
  readonly certificates: Map<string, string>;
  /** Google's `Cache-Control: max-age`, when it sent one. */
  readonly maxAgeMs: number | null;
}

export type CertificateSource = () => Promise<CertificateSet>;

const CERTIFICATES_URL =
  'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';

/**
 * How long the certificates are cached, when Google does not say.
 *
 * Google's response carries `Cache-Control: max-age`, and that header is always
 * preferred — it is how a key rotation is picked up. This is only the fallback
 * for a response without one, and it is short enough that a rotation is
 * noticed within the token lifetime rather than after it.
 */
const DEFAULT_CERT_TTL_MS = 60 * 60 * 1000;

/**
 * Verify a Firebase ID token against Google's published certificates.
 *
 * `projectId` is the only configuration, and it is public: it is the token's
 * `aud` claim, and checking it is what stops a token minted for a *different*
 * Firebase project from being accepted here.
 */
export function createFirebaseVerifier(
  projectId: string | undefined,
  certificateSource: CertificateSource = defaultCertificateSource
): IdentityVerifier {
  if (projectId === undefined || projectId === '') return unconfiguredVerifier();

  const issuer = `https://securetoken.google.com/${projectId}`;

  // Cached across requests: they change on Google's schedule, not ours.
  let cache: { certificates: Map<string, string>; expiresAt: number } | null = null;

  async function certificates(): Promise<Map<string, string>> {
    if (cache !== null && cache.expiresAt > Date.now()) return cache.certificates;

    const fetched = await certificateSource();
    cache = {
      certificates: fetched.certificates,
      expiresAt: Date.now() + (fetched.maxAgeMs ?? DEFAULT_CERT_TTL_MS),
    };
    return fetched.certificates;
  }

  return {
    configured: true,

    async verify(idToken: string): Promise<VerifiedIdentity> {
      // The header is read WITHOUT verification, to learn which key signed it.
      // This is the standard first step for a rotatable key set and is not a
      // trust decision: `kid` selects a candidate, and the signature check below
      // is what rejects a candidate that did not sign this token.
      const decoded = jwt.decode(idToken, { complete: true });
      const kid = decoded?.header.kid;
      if (typeof kid !== 'string' || kid === '') throw unauthorized('invalid_token', 'No key id.');

      const material = (await certificates()).get(kid);
      if (material === undefined) {
        // An unknown kid means a rotation we have not seen. The cache is dropped
        // so the NEXT request refetches rather than repeating this failure for
        // the rest of the TTL.
        cache = null;
        throw unauthorized('invalid_token', 'Unknown signing key.');
      }

      let payload: string | jwt.JwtPayload;
      try {
        payload = jwt.verify(idToken, verificationKeyOf(material), {
          algorithms: ['RS256'],
          audience: projectId,
          issuer,
        });
      } catch (err) {
        // One failure mode to the caller. Expired, not-yet-valid, wrong
        // signature, wrong project, wrong issuer and tampered payload are all
        // "this token is not good", and naming which would tell an attacker
        // which part to keep working on.
        throw unauthorized('invalid_token', (err as Error).message);
      }

      if (typeof payload === 'string') throw unauthorized('invalid_token', 'Malformed payload.');

      const uid = payload.sub;
      // Firebase always sets `sub` to the uid, and an empty one would key every
      // unknown caller onto the same reader row — the failure this check exists
      // to make impossible.
      if (typeof uid !== 'string' || uid === '') throw unauthorized('invalid_token', 'No subject.');

      const provider = firebaseProvider(payload);
      const email = typeof payload.email === 'string' && payload.email !== '' ? payload.email : null;

      return {
        uid,
        // A creator account carries an email, but the provider claim is the
        // authority, not the presence of the field.
        email: provider === 'anonymous' ? null : email,
        anonymous: provider === 'anonymous',
        // Never true for an anonymous uid, whatever the token says: there is no
        // address to verify, and a claim on a token with no identity behind it
        // is exactly what must not be believed.
        emailVerified: provider !== 'anonymous' && payload['email_verified'] === true,
      };
    },
  };
}

/**
 * §16's creator accounts: Firestore-side this is the same token, and the
 * difference is only the sign-in provider.
 *
 * Anything other than `anonymous` is treated as an identified account, because
 * the providers that are not anonymous all identify somebody — email/password
 * today, a federated provider later. The default when the claim is missing is
 * `anonymous`, so a token without it is granted the LESSER set of abilities.
 */
function firebaseProvider(payload: jwt.JwtPayload): string {
  const firebase = payload['firebase'];
  if (typeof firebase !== 'object' || firebase === null) return 'anonymous';
  const provider = (firebase as Record<string, unknown>)['sign_in_provider'];
  return typeof provider === 'string' && provider !== '' ? provider : 'anonymous';
}

/**
 * The public key a signature is checked against.
 *
 * Google has published its rotating keys in two shapes and both are handled
 * here rather than only the one in use today: an X.509 certificate (what the
 * `securetoken` endpoint serves) and a bare public key (what a JWKS endpoint
 * serves). A certificate is not a key — the key has to be extracted from it —
 * so the two shapes are not interchangeable, and sniffing which one arrived is
 * cheaper than being wrong about it after a change on Google's side.
 */
function verificationKeyOf(material: string): string {
  if (!material.includes('BEGIN CERTIFICATE')) return material;

  try {
    const pem = new X509Certificate(material).publicKey.export({ type: 'spki', format: 'pem' });
    // `format: 'pem'` is a string, but the overload is declared as string |
    // Buffer, so both shapes are handled rather than asserted away.
    return typeof pem === 'string' ? pem : pem.toString('utf8');
  } catch {
    // A certificate we cannot parse is not something the caller can act on
    // differently, and caching it would not help, so it reads as a bad token.
    throw unauthorized('invalid_token', 'Unreadable signing certificate.');
  }
}

/** The real source. Network failure is a 503, not a 401 — retrying may work. */
const defaultCertificateSource: CertificateSource = async () => {
  let response: Response;
  try {
    response = await fetch(CERTIFICATES_URL);
  } catch (err) {
    throw serviceUnavailable('auth_unavailable', `Could not reach the signing keys: ${(err as Error).message}`);
  }

  if (!response.ok) {
    throw serviceUnavailable('auth_unavailable', `Signing keys returned ${response.status}.`);
  }

  const body = (await response.json()) as Record<string, unknown>;
  const certificates = new Map<string, string>();
  for (const [kid, certificate] of Object.entries(body)) {
    if (typeof certificate === 'string') certificates.set(kid, certificate);
  }

  if (certificates.size === 0) {
    throw serviceUnavailable('auth_unavailable', 'Signing keys were empty.');
  }

  return { certificates, maxAgeMs: maxAgeOf(response.headers.get('cache-control')) };
};

/**
 * `Cache-Control: public, max-age=21434` → 21 434 000.
 *
 * Read rather than assumed, because it is how a rotating key set announces
 * itself: honouring a shorter value than our default picks up a new key sooner,
 * and honouring a longer one avoids refetching keys that have not moved.
 */
function maxAgeOf(header: string | null): number | null {
  const match = header === null ? null : /(?:^|,)\s*max-age=(\d+)/.exec(header);
  if (match === null) return null;
  const seconds = Number(match[1]);
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : null;
}

/**
 * The verifier a deployment without `FIREBASE_PROJECT_ID` gets.
 *
 * Deliberately refusal rather than permissiveness: reader-scoped routes cannot
 * be served correctly without knowing whose state is being asked for, and the
 * alternative to refusing — trusting a uid the client supplied — is the one
 * outcome that must not be possible. Every public read still works, so an
 * unconfigured deployment degrades to exactly the product 001 described.
 */
export function unconfiguredVerifier(): IdentityVerifier {
  return {
    configured: false,
    async verify(): Promise<VerifiedIdentity> {
      throw serviceUnavailable(
        'auth_unavailable',
        'FIREBASE_PROJECT_ID is not set, so reader sign-in cannot be verified.'
      );
    },
  };
}
