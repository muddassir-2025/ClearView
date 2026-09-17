import { generateKeyPairSync } from 'node:crypto';
import jwt from 'jsonwebtoken';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/http/errors.js';
import {
  createFirebaseVerifier,
  unconfiguredVerifier,
  type CertificateSet,
} from '../src/identity/verifier.js';

/**
 * Reader identity (§3, §15, §16).
 *
 * The uid that comes out of this decides whose follows, whose read position and
 * whose messages a request touches, so this is the one file in the product where
 * being wrong reads someone else's data. It is tested against a key pair
 * generated HERE rather than a captured token, which is what makes the checks
 * testable at all: a real token cannot be made to expire, to be signed by the
 * wrong key, or to name another Firebase project on demand.
 *
 * Every rejection case asserts the CLIENT-VISIBLE code, not just that something
 * threw, because the code is what the app branches on to decide whether to sign
 * in again or to retry later.
 */

const PROJECT_ID = 'clearview-test';
const ISSUER = `https://securetoken.google.com/${PROJECT_ID}`;
const KID = 'test-key-1';

/**
 * A key pair for this suite, in the PEM form a token library accepts.
 *
 * Generated per run rather than checked in: a fixture private key in a
 * repository is a fixture private key somebody eventually reuses for something
 * real, and generation costs a few milliseconds.
 */
function keyPair(): { privateKey: string; publicKey: string } {
  return generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
}

const { privateKey, publicKey } = keyPair();

/** The other key pair: nothing signed with it should ever be accepted. */
const stranger = keyPair();

/** A source that always has one key, expiring on our terms. */
function sourceOf(publicKeyPem: string, kid = KID): () => Promise<CertificateSet> {
  return async () => ({
    certificates: new Map([[kid, publicKeyPem]]),
    maxAgeMs: 60_000,
  });
}

interface TokenOptions {
  uid?: string;
  provider?: string;
  email?: string;
  audience?: string;
  issuer?: string;
  /** Seconds from now. Negative values produce an already-expired token. */
  expiresIn?: number;
  key?: string;
  kid?: string;
  /** Firebase's own claim, and the only thing that makes an email trustworthy. */
  emailVerified?: boolean;
}

function token(options: TokenOptions = {}): string {
  // Typed as the library's own payload shape rather than a bare object, because
  // `jwt.sign`'s overloads are resolved by the payload type and a plain
  // `Record<string, unknown>` selects the callback form.
  const payload: jwt.JwtPayload = {
    sub: options.uid ?? 'anon-uid-1',
    firebase: { sign_in_provider: options.provider ?? 'anonymous' },
  };
  if (options.email !== undefined) payload['email'] = options.email;
  if (options.emailVerified !== undefined) payload['email_verified'] = options.emailVerified;

  return jwt.sign(payload, options.key ?? privateKey, {
    algorithm: 'RS256',
    keyid: options.kid ?? KID,
    audience: options.audience ?? PROJECT_ID,
    issuer: options.issuer ?? ISSUER,
    expiresIn: options.expiresIn ?? 3600,
  });
}

/** The error code a call failed with. */
async function codeOf(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (err) {
    if (err instanceof ApiError) return err.type;
    throw err;
  }
  throw new Error('Expected the call to fail.');
}

describe('verifying a Firebase ID token', () => {
  it('reads the uid, the email and the provider from a genuine token', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));

    const reader = await verifier.verify(token());
    expect(reader).toEqual({
      uid: 'anon-uid-1',
      email: null,
      anonymous: true,
      emailVerified: false,
    });

    // §16: the same verification path, one different claim. This is the whole
    // difference between a reader and a creator at this layer.
    const creator = await verifier.verify(token({ provider: 'password', email: 'maker@example.test' }));
    expect(creator).toEqual({
      uid: 'anon-uid-1',
      email: 'maker@example.test',
      anonymous: false,
      emailVerified: false,
    });

    // And the claim that decides whether an address may be attached to an
    // EXISTING account: a Google address is proven, a typed one is not.
    const google = await verifier.verify(
      token({ provider: 'google.com', email: 'maker@example.test', emailVerified: true })
    );
    expect(google.emailVerified).toBe(true);
  });

  it('never treats an anonymous uid as having a proven address', async () => {
    // A token that claims both must not be believed: there is no address behind
    // an anonymous identity, so there is nothing for the claim to be about.
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));

    const reader = await verifier.verify(token({ email: 'maker@example.test', emailVerified: true }));

    expect(reader.anonymous).toBe(true);
    expect(reader.email).toBeNull();
    expect(reader.emailVerified).toBe(false);
  });

  it('never reports an email for an anonymous uid', async () => {
    // A token that somehow carried both must not be treated as an account: the
    // email is only meaningful for a provider that verified one.
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));
    const reader = await verifier.verify(token({ email: 'unverified@example.test' }));
    expect(reader.email).toBeNull();
    expect(reader.anonymous).toBe(true);
  });

  it('assumes the lesser identity when the provider claim is missing', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));
    const payload = { sub: 'anon-uid-1', email: 'someone@example.test' };
    const bare = jwt.sign(payload, privateKey, {
      algorithm: 'RS256',
      keyid: KID,
      audience: PROJECT_ID,
      issuer: ISSUER,
      expiresIn: '1h',
    });

    const reader = await verifier.verify(bare);
    expect(reader.anonymous).toBe(true);
    expect(reader.email).toBeNull();
  });

  it('refuses a token signed by another key', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));
    const forged = token({ key: stranger.privateKey });
    expect(await codeOf(() => verifier.verify(forged))).toBe('invalid_token');
  });

  it('refuses an expired token, and one from another Firebase project', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));

    const expired = token({ expiresIn: -1 });
    expect(await codeOf(() => verifier.verify(expired))).toBe('invalid_token');

    // The check that stops a token minted for ANY Firebase project being used
    // here: without an audience check, every project's tokens would be valid.
    const wrongAudience = token({ audience: 'some-other-project' });
    expect(await codeOf(() => verifier.verify(wrongAudience))).toBe('invalid_token');

    const wrongIssuer = token({ issuer: 'https://securetoken.google.com/some-other-project' });
    expect(await codeOf(() => verifier.verify(wrongIssuer))).toBe('invalid_token');
  });

  it('refuses a token whose payload was edited, and one with no subject', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));

    // The classic attack: rewrite the payload to name the uid you want and keep
    // the original signature. A verifier that decoded without checking would
    // read `someone-elses-uid` straight out of this.
    const genuine = token({ uid: 'my-own-uid' });
    const [header, , signature] = genuine.split('.');
    const forgedPayload = Buffer.from(
      JSON.stringify({
        sub: 'someone-elses-uid',
        firebase: { sign_in_provider: 'anonymous' },
        audience: PROJECT_ID,
        issuer: ISSUER,
        exp: Math.floor(Date.now() / 1000) + 3600,
      })
    ).toString('base64url');

    const tampered = `${header}.${forgedPayload}.${signature}`;
    expect(tampered).not.toBe(genuine);
    expect(await codeOf(() => verifier.verify(tampered))).toBe('invalid_token');

    // An empty subject would key every unknown caller onto one reader row.
    const noSubject = jwt.sign({ firebase: { sign_in_provider: 'anonymous' } }, privateKey, {
      algorithm: 'RS256',
      keyid: KID,
      audience: PROJECT_ID,
      issuer: ISSUER,
      expiresIn: '1h',
    });
    expect(await codeOf(() => verifier.verify(noSubject))).toBe('invalid_token');
  });

  it('refuses something that is not a token at all', async () => {
    const verifier = createFirebaseVerifier(PROJECT_ID, sourceOf(publicKey));
    expect(await codeOf(() => verifier.verify('not-a-jwt'))).toBe('invalid_token');
  });

  it('refetches the keys after an unknown key id, so a rotation is picked up', async () => {
    // Google rotates its keys; a token signed with the new one arrives BEFORE
    // this process has ever seen it. Treating that as invalid forever would
    // break every reader the moment the rotation happened.
    const rotated = keyPair();
    let calls = 0;
    const verifier = createFirebaseVerifier(PROJECT_ID, async () => {
      calls += 1;
      // The key set before the rotation names only the old kid; the set after it
      // names the new one. A first fetch that already knew `key-2` would prove
      // nothing about what happens when it does not.
      const certificates =
        calls === 1
          ? new Map([[KID, publicKey]])
          : new Map([['key-2', rotated.publicKey]]);
      return { certificates, maxAgeMs: 60_000 };
    });

    const rotatedToken = token({ key: rotated.privateKey, kid: 'key-2' });

    // An unknown kid is refused — there is no key to check the signature with —
    // but the cached set is dropped on the way out.
    expect(await codeOf(() => verifier.verify(rotatedToken))).toBe('invalid_token');
    expect(calls).toBe(1);

    // So the NEXT request refetches and finds the rotation, rather than being
    // refused for the rest of the cache's lifetime.
    const reader = await verifier.verify(rotatedToken);
    expect(reader.uid).toBe('anon-uid-1');
    expect(calls).toBe(2);
  });

  it('caches the keys rather than fetching them per request', async () => {
    let calls = 0;
    const verifier = createFirebaseVerifier(PROJECT_ID, async () => {
      calls += 1;
      return { certificates: new Map([[KID, publicKey]]), maxAgeMs: 60_000 };
    });

    await verifier.verify(token());
    await verifier.verify(token());
    await verifier.verify(token());

    expect(calls).toBe(1);
  });
});

describe('a deployment with no Firebase project id', () => {
  it('reports itself unconfigured and refuses every token', async () => {
    const verifier = unconfiguredVerifier();
    expect(verifier.configured).toBe(false);
    // 503, not 401: the deployment is incomplete rather than the caller being
    // wrong, and the Android client words those two differently.
    expect(await codeOf(() => verifier.verify('anything'))).toBe('auth_unavailable');

    // The same for a verifier built with no project id at all.
    const built = createFirebaseVerifier(undefined);
    expect(built.configured).toBe(false);
    expect(await codeOf(() => built.verify('anything'))).toBe('auth_unavailable');
  });
});
