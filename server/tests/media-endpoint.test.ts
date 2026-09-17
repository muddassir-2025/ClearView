import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * Where a presigned upload actually points (§14).
 *
 * The bucket is the one thing a deployment can swap without touching code:
 * Amazon S3 by default, or any S3-compatible service when `AWS_ENDPOINT_URL` is
 * set. That second path exists because egress is what an image product pays for,
 * and the providers that do not charge it (Cloudflare R2, Backblaze B2, Wasabi)
 * all speak the same protocol — so the switch is an endpoint and nothing else.
 *
 * These assert the HOSTNAME rather than "it returned a URL", because a presigned
 * URL is signed for one host: pointing it at the wrong one produces a link that
 * looks entirely correct and 403s the moment a client uses it. Both directions
 * are asserted for the same reason — a switch that silently sent Amazon
 * deployments to someone else's endpoint would be worse than no switch.
 *
 * The module is re-imported for each case because `src/env.ts` reads and
 * validates `process.env` once, at import time.
 */
async function presignedHost(overrides: Record<string, string>): Promise<string> {
  vi.resetModules();
  for (const [key, value] of Object.entries(overrides)) vi.stubEnv(key, value);

  const { createObjectStore } = await import('../src/media/store.js');
  const upload = await createObjectStore().presignUpload({
    key: 'goodpost/channels/00000000-0000-4000-8000-000000000000/media/x.png',
    contentType: 'image/png',
    byteSize: 1024,
  });

  return new URL(upload.url).host;
}

describe('media storage endpoint', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('sends presigned uploads to Amazon S3 when no endpoint is configured', async () => {
    expect(
      await presignedHost({
        AWS_S3_BUCKET: 'clearview-test',
        AWS_ACCESS_KEY_ID: 'AKIATESTTESTTEST',
        AWS_SECRET_ACCESS_KEY: 'test-secret',
        AWS_REGION: 'eu-central-1',
        AWS_ENDPOINT_URL: '',
      })
    ).toBe('clearview-test.s3.eu-central-1.amazonaws.com');
  });

  it('sends them to an S3-compatible provider when one is configured', async () => {
    // R2's virtual-hosted form, which is what its own documentation uses.
    expect(
      await presignedHost({
        AWS_S3_BUCKET: 'clearview-test',
        AWS_ACCESS_KEY_ID: 'AKIATESTTESTTEST',
        AWS_SECRET_ACCESS_KEY: 'test-secret',
        AWS_REGION: 'auto',
        AWS_ENDPOINT_URL: 'https://account-id.r2.cloudflarestorage.com',
      })
    ).toBe('clearview-test.account-id.r2.cloudflarestorage.com');
  });

  it('refuses an upload with media_unavailable when no bucket is configured', async () => {
    // The state every deployment starts in, and the one the composer words as
    // "images are not available on this server" rather than a broken image.
    vi.resetModules();
    vi.stubEnv('AWS_S3_BUCKET', '');
    const { createObjectStore, UnconfiguredObjectStore } = await import('../src/media/store.js');

    expect(createObjectStore()).toBeInstanceOf(UnconfiguredObjectStore);
    await expect(
      createObjectStore().presignUpload({
        key: 'goodpost/channels/00000000-0000-4000-8000-000000000000/media/x.png',
        contentType: 'image/png',
        byteSize: 1024,
      })
    ).rejects.toMatchObject({ type: 'media_unavailable', status: 503 });
  });
});
