import { cert, getApps, initializeApp, type App } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { env, isProduction } from '../env.js';
import { serviceUnavailable, unauthorized } from '../http/errors.js';

/**
 * Turning a Firebase ID token into a phone identity (§44: the OTP provider is
 * abstracted behind a small interface, so the rest of the auth code never
 * mentions Firebase).
 *
 * The division of labour matters:
 *
 *  - Firebase sends and checks the SMS code. We never see an OTP, and we must
 *    never be the thing that decides whether a code was right.
 *  - This module's ONLY job is to answer "which verified phone number does
 *    this token prove ownership of?". Everything downstream (hashing, ban
 *    checks, session creation) works from that answer and is provider-neutral.
 *
 * What the client gets back from the Admin SDK is cryptographically attested
 * by Google, which is why the phone number here is trustworthy in a way a
 * client-supplied `phone` field never could be (§“Never trust … supplied
 * directly by the Android client”).
 */

export interface VerifiedPhone {
  /** E.164, e.g. "+923001234567". Immediately hashed; never persisted raw. */
  readonly phoneE164: string;
  readonly firebaseUid: string;
}

export interface PhoneIdentityVerifier {
  readonly kind: 'firebase' | 'disabled';
  verifyIdToken(idToken: string): Promise<VerifiedPhone>;
}

/** E.164: a leading +, no leading zero, 7–15 digits total. */
const E164 = /^\+[1-9]\d{6,14}$/;

/**
 * An error's Firebase code, for logging.
 *
 * Only ever the CODE (e.g. `app/invalid-credential`). The SDK's message is not
 * logged because it can quote back the input it was given, and the input here
 * is an ID token — a live credential.
 */
function firebaseErrorCode(err: unknown): string | null {
  const code = (err as { code?: unknown } | null)?.code;
  return typeof code === 'string' && code.length > 0 ? code : null;
}

/** True when the Firebase Admin service account is present. */
export const firebaseConfigured = Boolean(
  env.FIREBASE_PROJECT_ID && env.FIREBASE_CLIENT_EMAIL && env.FIREBASE_PRIVATE_KEY
);

let cachedApp: App | null = null;

/**
 * Initialise the Admin SDK on first use rather than at import.
 *
 * Deliberately lazy: `buildApp()` is imported by tests and by tooling that has
 * no service account, and failing at import time would make the module
 * unusable there. A missing service account surfaces as a 503 on the one
 * endpoint that actually needs it, which is also the honest answer to give a
 * client — the capability is unavailable, not the request malformed.
 *
 * Exported so verification tooling can exercise THIS initialisation rather
 * than building a second Firebase app of its own, which would prove nothing
 * about the code the service actually runs.
 */
export function getFirebaseAdminApp(): App {
  if (cachedApp) return cachedApp;

  // A second initializeApp() with a different name throws; reuse the default
  // app if something else (FCM, later) already created it.
  const existing = getApps()[0];
  if (existing) {
    cachedApp = existing;
    return existing;
  }

  cachedApp = initializeApp({
    credential: cert({
      projectId: env.FIREBASE_PROJECT_ID,
      clientEmail: env.FIREBASE_CLIENT_EMAIL,
      // env.ts already restored the escaped newlines in the PEM key.
      privateKey: env.FIREBASE_PRIVATE_KEY,
    }),
  });
  return cachedApp;
}

class FirebasePhoneVerifier implements PhoneIdentityVerifier {
  readonly kind = 'firebase' as const;

  /**
   * [adminApp] is injected so the initialisation-failure path can be exercised
   * without a real service account, the same way every other module here takes
   * its collaborator as an argument rather than reaching for a global.
   */
  constructor(private readonly adminApp: () => App = getFirebaseAdminApp) {}

  async verifyIdToken(idToken: string): Promise<VerifiedPhone> {
    if (!firebaseConfigured) {
      throw serviceUnavailable(
        'auth_unavailable',
        'Firebase Admin is not configured on this server.'
      );
    }

    // Initialising the SDK is deliberately separated from verifying a token.
    // While both sat inside one `try`, a malformed service account — an
    // operator mistake that breaks EVERY sign-in identically — was reported to
    // the user as "your identity token is not valid". That is false, it blames
    // the one thing the user cannot fix, and it left no trace anywhere. The
    // capability genuinely is unavailable, so it is reported as such.
    let admin;
    try {
      admin = getAuth(this.adminApp());
    } catch (err) {
      console.error(
        '[firebase] Admin SDK could not be initialised (check FIREBASE_PROJECT_ID, ' +
          'FIREBASE_CLIENT_EMAIL and FIREBASE_PRIVATE_KEY):',
        firebaseErrorCode(err) ?? '<no code>'
      );
      throw serviceUnavailable(
        'auth_unavailable',
        'Firebase Admin could not be initialised on this server.'
      );
    }

    let decoded;
    try {
      // checkRevoked costs one extra round trip but makes a token invalidated
      // in the Firebase console (or by an account disable) fail here rather
      // than staying valid for its remaining hour.
      decoded = await admin.verifyIdToken(idToken, true);
    } catch (err) {
      // The SDK's reason is not echoed to the caller: it distinguishes
      // "expired" from "bad signature", and a caller holding a forged token
      // should not get that oracle. It IS logged, by code alone, because
      // without it a rejection is indistinguishable from a misconfiguration.
      console.warn('[firebase] ID token rejected:', firebaseErrorCode(err) ?? '<no code>');
      throw unauthorized('invalid_id_token', 'The supplied identity token is not valid.');
    }

    // A token for a different provider (Google, email/password, anonymous)
    // carries no verified phone number and must not be treated as one.
    if (decoded.firebase?.sign_in_provider !== 'phone') {
      throw unauthorized('wrong_sign_in_provider', 'Expected a phone sign-in token.');
    }

    const phone = decoded.phone_number;
    if (typeof phone !== 'string' || !E164.test(phone)) {
      throw unauthorized('phone_unverified', 'The token carries no verified phone number.');
    }

    return { phoneE164: phone, firebaseUid: decoded.uid };
  }
}

/**
 * A verifier that accepts `phone:<E.164>` in place of a real token.
 *
 * This exists so the whole auth pipeline — hashing, ban checks, sessions,
 * rotation — can be exercised in tests and on a staging box with no Firebase
 * project. Three things keep it from becoming a hole:
 *
 *  1. It is never selected implicitly; PHONE_VERIFY_MODE must be "disabled".
 *  2. env.ts REFUSES TO BOOT when it is selected in production, so a deployed
 *     service cannot reach this code by accident.
 *  3. It only accepts tokens with the literal `phone:` prefix, so a real
 *     Firebase token (a long JWT) can never be mistaken for one.
 */
class DisabledPhoneVerifier implements PhoneIdentityVerifier {
  readonly kind = 'disabled' as const;

  // Referenced so the production guard below cannot be dead-code eliminated,
  // and so this class's precondition is visible where it is defined.
  static readonly productionSafe = false;

  async verifyIdToken(idToken: string): Promise<VerifiedPhone> {
    if (isProduction) {
      // Belt-and-braces: env.ts already refuses to start, but if this class is
      // ever reached another way, it fails closed rather than trusting input.
      throw serviceUnavailable('auth_unavailable', 'Phone verification is disabled.');
    }
    const phone = idToken.startsWith('phone:') ? idToken.slice('phone:'.length) : '';
    if (!E164.test(phone)) {
      throw unauthorized('invalid_id_token', 'Expected "phone:<E.164>".');
    }
    return { phoneE164: phone, firebaseUid: `disabled:${phone}` };
  }
}

/** The verifier selected by PHONE_VERIFY_MODE. */
export function createPhoneVerifier(): PhoneIdentityVerifier {
  return env.PHONE_VERIFY_MODE === 'disabled'
    ? new DisabledPhoneVerifier()
    : new FirebasePhoneVerifier();
}

/**
 * A verifier whose Admin app resolution is supplied by the caller.
 *
 * Exported for the same reason [getFirebaseAdminApp] is: the failure this
 * guards against — a misconfigured service account being reported to a user as
 * their own invalid token — can only be exercised by controlling how the app
 * resolves, and letting a test build its own Firebase app would prove nothing
 * about the code the service runs.
 */
export function phoneVerifierWithAdminApp(adminApp: () => App): PhoneIdentityVerifier {
  return new FirebasePhoneVerifier(adminApp);
}
