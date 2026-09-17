import type { Express } from 'express';
import request from 'supertest';
import { expect } from 'vitest';
import { TEST_PASSWORD, insertAdmin } from './database.js';

/**
 * Driving the real routes from the test suite.
 *
 * Every setup step here goes through the actual HTTP surface — sign-in through
 * `POST /admin/api/auth/login`, a channel through `POST /admin/api/channels` —
 * rather than inserting rows directly. A suite that fabricates its fixtures
 * tests the SQL it wrote, not the flow a client takes, and the flow is where
 * the interesting mistakes are.
 *
 * The one exception is the account itself: §17 says the first super
 * administrator comes from the deployment's environment and there is no route
 * that creates one (the absence of such a route is asserted rather than
 * assumed), so a fixture stands in for that configuration.
 *
 * Each helper takes the app rather than closing over one, so a test can build a
 * second app (a different store, a different limiter) and drive both.
 */

export interface Session {
  readonly accessToken: string;
  readonly adminId: string;
  readonly role: string;
  readonly channelId: string | null;
}

/** Sign in through the real endpoint, so the token under test is a real one. */
export async function signIn(
  app: Express,
  email: string,
  password: string = TEST_PASSWORD
): Promise<Session> {
  const res = await request(app).post('/admin/api/auth/login').send({ email, password });

  expect(res.status, `sign-in should succeed in a fixture (${JSON.stringify(res.body)})`).toBe(200);
  return {
    accessToken: res.body.accessToken as string,
    adminId: (res.body.admin as { id: string }).id,
    role: (res.body.admin as { role: string }).role,
    channelId: (res.body.admin as { channelId: string | null }).channelId,
  };
}

/** A super administrator, signed in. */
export async function superAdminSession(
  app: Express,
  pglite: Parameters<typeof insertAdmin>[0]
): Promise<Session> {
  const email = `super-${Math.random().toString(36).slice(2, 10)}@example.test`;
  await insertAdmin(pglite, { email, email_normalized: email, role: 'super_admin' });
  return signIn(app, email);
}

export const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

export interface ChannelFixture {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
}

/**
 * Create a channel AND the administrator that runs it, in one request (§20).
 *
 * This is the flow the app's "Create a channel" screen performs, so a fixture
 * that uses it cannot drift from what the product actually does — including the
 * part that matters most, which is that both exist or neither does.
 */
export async function createChannelWithAdmin(
  app: Express,
  session: Session,
  name: string
): Promise<{ channel: ChannelFixture; adminEmail: string; password: string }> {
  const adminEmail = `owner-${Math.random().toString(36).slice(2, 10)}@example.test`;
  const password = 'a-channel-admin-password';

  const res = await request(app)
    .post('/admin/api/channels')
    .set(authed(session.accessToken))
    .send({
      name,
      description: 'A test channel',
      adminEmail,
      adminPassword: password,
    });

  expect(res.status, `channel creation should succeed (${JSON.stringify(res.body)})`).toBe(201);
  const channel = res.body.channel as ChannelFixture;
  return { channel, adminEmail, password };
}

/** Create a channel the super administrator runs themselves. */
export async function createBareChannel(
  app: Express,
  session: Session,
  name: string
): Promise<ChannelFixture> {
  const res = await request(app)
    .post('/admin/api/channels')
    .set(authed(session.accessToken))
    .send({ name });

  expect(res.status, `channel creation should succeed (${JSON.stringify(res.body)})`).toBe(201);
  return res.body.channel as ChannelFixture;
}

/** Publish a text post and return its id. */
export async function publishTextPost(
  app: Express,
  session: Session,
  channelId: string,
  body: string
): Promise<string> {
  const res = await request(app)
    .post(`/admin/api/channels/${channelId}/posts`)
    .set(authed(session.accessToken))
    .send({ body });

  expect(res.status, `publishing should succeed (${JSON.stringify(res.body)})`).toBe(201);
  return (res.body.post as { id: string }).id;
}
