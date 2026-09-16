import { describe, expect, it } from 'vitest';
import { firebaseConfigured, phoneVerifierWithAdminApp } from '../src/auth/firebase.js';

/**
 * The Firebase phone verifier's failure reporting.
 *
 * This one case is worth a test file of its own because it is the difference
 * between two very different sentences shown to a user who did nothing wrong,
 * and because the wrong one hides an operator mistake indefinitely:
 *
 *   - `auth_unavailable` — this deployment cannot verify anyone. Nothing the
 *     user does will help, so don't send them round again.
 *   - `invalid_id_token` — the caller's credential is bad.
 *
 * The FIREBASE_* values in vitest.config.ts are placeholders, which is what
 * makes `firebaseConfigured` true here. Nothing in this file talks to Google:
 * the Admin app resolution is injected, and it throws before `cert()` is ever
 * reached.
 */
describe('firebase phone verifier', () => {
  it('reports a broken Admin initialisation as unavailable, not as the user\'s token', async () => {
    let appResolutions = 0;
    const verifier = phoneVerifierWithAdminApp(() => {
      appResolutions += 1;
      // What a service account does when the PEM lost its newlines — the
      // failure `scripts/set-firebase-env.mjs --repair` exists to undo.
      throw new Error('DECODER routines::unsupported');
    });

    await expect(verifier.verifyIdToken('some-token')).rejects.toMatchObject({
      status: 503,
      type: 'auth_unavailable',
    });

    // The guard that keeps this assertion honest. If the environment were ever
    // unconfigured, verifyIdToken would return the SAME 503 without consulting
    // the app at all, and the test above would pass while proving nothing.
    expect(appResolutions).toBe(1);
  });

  it('is configured in the suite, so the check above cannot skip', () => {
    expect(firebaseConfigured).toBe(true);
  });
});
