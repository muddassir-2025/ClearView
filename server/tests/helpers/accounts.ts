import type { Express } from 'express';
import request from 'supertest';
import { expect } from 'vitest';
import type { PhoneIdentityVerifier } from '../../src/auth/firebase.js';
import { unauthorized } from '../../src/http/errors.js';

/**
 * Driving the real routes from the test suite.
 *
 * Every setup step here goes through the actual HTTP surface — registration
 * through the OTP and register endpoints, a channel through `POST
 * /api/v1/channels` — rather than inserting rows directly. A suite that
 * fabricates its fixtures tests the SQL it wrote, not the flow a client takes,
 * and the flow is where the interesting mistakes are.
 *
 * Each helper takes the app rather than closing over one, so a test can build a
 * second app (a different store, a different verifier) and drive both.
 */

/**
 * Stands in for Firebase, rejecting exactly what the real verifier rejects so
 * no test can pass by having a lenient double.
 *
 * The token format is `test:+<E.164>`: the phone number Firebase would have
 * verified, prefixed so a test cannot accidentally send a real ID token and
 * quietly pass for the wrong reason.
 */
export function fakeVerifier(): PhoneIdentityVerifier {
  return {
    kind: 'firebase',
    async verifyIdToken(idToken: string) {
      const phone = idToken.startsWith('test:') ? idToken.slice('test:'.length) : '';
      if (!/^\+[1-9]\d{6,14}$/.test(phone)) {
        throw unauthorized('invalid_id_token', 'The supplied identity token is not valid.');
      }
      return { phoneE164: phone, firebaseUid: `uid:${phone}` };
    },
  };
}

export const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

/** Registers a user through the real flow and returns a live session. */
export async function registeredIn(
  app: Express,
  phone: string,
  name = 'Owner',
  email = `${phone}@example.test`
): Promise<{ accessToken: string; refreshToken: string; user: { id: string } }> {
  await request(app).post('/api/v1/auth/otp/request').send({ phone, purpose: 'register' });

  const res = await request(app)
    .post('/api/v1/auth/register')
    .send({ idToken: `test:${phone}`, displayName: name, email });

  expect(res.status, 'registration should succeed in a fixture').toBe(201);
  return res.body as { accessToken: string; refreshToken: string; user: { id: string } };
}

export interface ChannelPayload {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  categorySlug: string | null;
  followerCount: number;
  postCount: number;
  viewerRole: string | null;
  shareLink: string;
  status: string;
  isFollowing: boolean;
  isBlocked: boolean;
  notificationsEnabled: boolean;
  hasUnread: boolean;
  lastPostAt: string | null;
}

/** Creates a channel as [session] and returns the created payload. */
export async function createChannelVia(
  app: Express,
  session: { accessToken: string },
  body: Record<string, unknown>
): Promise<ChannelPayload> {
  const res = await request(app)
    .post('/api/v1/channels')
    .set(authed(session.accessToken))
    .send(body);

  expect(res.status, 'channel creation should succeed in a fixture').toBe(201);
  return res.body.channel as ChannelPayload;
}
