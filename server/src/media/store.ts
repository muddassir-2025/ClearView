import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { env, s3Configured } from '../env.js';
import { serviceUnavailable, type ApiError } from '../http/errors.js';

/**
 * Object storage for post media (§9).
 *
 * Two rules shape this module:
 *
 *  * **Credentials never leave the backend.** The client receives a presigned
 *    URL, which is a capability limited to one object, one method and one
 *    expiry. It never receives a key, and it never talks to S3 with a
 *    credential of ours.
 *
 *  * **Keys are generated, never accepted.** [mediaObjectKey] is derived from
 *    the media row's own uuid, so no request body can influence where an object
 *    lands. A client-chosen key is the standard way one account overwrites or
 *    claims another account's object, and there is no upside to allowing it.
 */

/** The kinds of media a post can carry (§8). Mirrors `media_kind` in 004. */
export type MediaKind = 'image' | 'video' | 'audio';

/**
 * The prefix every key this service creates begins with.
 *
 * The layout in §9 — `goodpost/channels/{images,videos,audio,avatars}` — is
 * reproduced here with the kind in the path so an operator can attach a
 * lifecycle rule or a budget alarm per kind without reading the database.
 */
export const MEDIA_KEY_PREFIX = 'goodpost/channels';

/**
 * Accepted content types, mapped to the extension a key ends in.
 *
 * An allow-list rather than a deny-list: this decides what the bucket will
 * serve back to a browser, and `text/html` or `image/svg+xml` stored under a
 * URL anyone can fetch is a stored-XSS primitive served from our own domain.
 */
export const ALLOWED_CONTENT_TYPES: Readonly<Record<string, string>> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
  'audio/mpeg': 'mp3',
  'audio/mp4': 'm4a',
  'audio/aac': 'aac',
  'audio/ogg': 'ogg',
  'audio/wav': 'wav',
};

/**
 * Strip the parameters from a content type and lower-case it.
 *
 * `image/jpeg; charset=utf-8` and `IMAGE/JPEG` are the same type, and a
 * parameter is meaningless for binary media. Without this, an accepted type
 * looks unlisted purely because a client sent it verbosely.
 */
export function normalizeContentType(value: string): string {
  return value.split(';')[0]?.trim().toLowerCase() ?? '';
}

/** The extension for a content type, or null when the type is not allowed. */
export function extensionFor(contentType: string): string | null {
  return ALLOWED_CONTENT_TYPES[normalizeContentType(contentType)] ?? null;
}

/** Which media kind a content type belongs to, or null when it is not allowed. */
export function kindFor(contentType: string): MediaKind | null {
  const base = normalizeContentType(contentType);
  if (!ALLOWED_CONTENT_TYPES[base]) return null;
  if (base.startsWith('image/')) return 'image';
  if (base.startsWith('video/')) return 'video';
  return 'audio';
}

/**
 * The one failure raised when this deployment has no bucket.
 *
 * Defined once and shared by [UnconfiguredObjectStore] and the media service:
 * a caller must not be able to tell whether it was the store or the service
 * that refused, and two hand-written copies of the message would eventually
 * disagree in a way the client branches on.
 */
export function mediaUnavailable(): ApiError {
  return serviceUnavailable(
    'media_unavailable',
    'Media storage is not configured on this server.'
  );
}

/**
 * The object key for a media row.
 *
 * Derived from the row's uuid, so it is unique by construction and stable for
 * the lifetime of the row. The kind that the caller passed is NOT used to build
 * the path from client input — [kindFor] and this function are both called with
 * the server's own decision about the content type.
 */
export function mediaObjectKey(kind: MediaKind, mediaId: string, extension: string): string {
  return `${MEDIA_KEY_PREFIX}/${kind}/${mediaId}.${extension}`;
}

/** A URL the client may PUT one object to, plus the headers it must send. */
export interface PresignedUpload {
  readonly url: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly expiresInSeconds: number;
}

/** What a HEAD on a stored object tells us. */
export interface StoredObject {
  readonly byteSize: number;
  readonly contentType: string | null;
}

/**
 * The storage operations posts need, as an interface.
 *
 * Injectable for the same reason the Firebase verifier is: the upload lifecycle
 * (presign → upload → confirm → claim) is the part that can be wrong, and it
 * must be testable without an AWS account. Tests supply a fake; production gets
 * the S3 implementation from [createObjectStore].
 */
export interface ObjectStore {
  /** False when no bucket is configured, so callers can degrade honestly. */
  readonly configured: boolean;

  presignUpload(input: {
    key: string;
    contentType: string;
    byteSize: number;
  }): Promise<PresignedUpload>;

  presignDownload(key: string): Promise<string>;

  /** Metadata for a stored object, or null when it is not there. */
  head(key: string): Promise<StoredObject | null>;

  /** Remove an object. Idempotent: removing a missing key is not an error. */
  remove(key: string): Promise<void>;
}

/**
 * The store used when S3 is not configured.
 *
 * Reads return nothing and deletes do nothing — with no bucket there can be no
 * object to find or remove, and making `remove` throw would crash the retention
 * sweep on a deployment that has no media at all. Writes refuse loudly, because
 * silently accepting an upload that goes nowhere would show a user a post with
 * a broken image and no explanation.
 */
export class UnconfiguredObjectStore implements ObjectStore {
  readonly configured = false;

  async presignUpload(): Promise<PresignedUpload> {
    throw mediaUnavailable();
  }

  async presignDownload(): Promise<string> {
    throw mediaUnavailable();
  }

  async head(): Promise<StoredObject | null> {
    return null;
  }

  async remove(): Promise<void> {
    // Nothing can exist in a bucket that was never configured.
  }
}

/** The S3-backed store. Constructed lazily so an unconfigured process never does. */
class S3ObjectStore implements ObjectStore {
  readonly configured = true;

  private readonly client: S3Client;
  private readonly bucket: string;

  constructor() {
    this.bucket = env.AWS_S3_BUCKET;

    // Credentials are passed explicitly only when configured. Otherwise the
    // SDK's own provider chain resolves them, which is the whole point of
    // AWS_USE_INSTANCE_ROLE: a role's temporary credentials, never a long-lived
    // key on disk.
    this.client = new S3Client({
      region: env.AWS_REGION,
      ...(env.AWS_USE_INSTANCE_ROLE
        ? {}
        : {
            credentials: {
              accessKeyId: env.AWS_ACCESS_KEY_ID,
              secretAccessKey: env.AWS_SECRET_ACCESS_KEY,
            },
          }),
    });
  }

  async presignUpload(input: {
    key: string;
    contentType: string;
    byteSize: number;
  }): Promise<PresignedUpload> {
    // ContentType and ContentLength are SIGNED, so the client cannot upload a
    // different type than the one that was validated, and cannot exceed the
    // size it declared. S3 rejects the request otherwise, which means the
    // authoritative size check happens at the bucket and not only in our code.
    const command = new PutObjectCommand({
      Bucket: this.bucket,
      Key: input.key,
      ContentType: input.contentType,
      ContentLength: input.byteSize,
    });

    const url = await getSignedUrl(this.client, command, {
      expiresIn: env.S3_UPLOAD_URL_TTL,
    });

    return {
      url,
      headers: { 'Content-Type': input.contentType },
      expiresInSeconds: env.S3_UPLOAD_URL_TTL,
    };
  }

  presignDownload(key: string): Promise<string> {
    // No ResponseContentDisposition: media is meant to be shown inline, and
    // forcing a download would break every image in the feed.
    return getSignedUrl(
      this.client,
      new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      { expiresIn: env.S3_DOWNLOAD_URL_TTL }
    );
  }

  async head(key: string): Promise<StoredObject | null> {
    try {
      const result = await this.client.send(
        new HeadObjectCommand({ Bucket: this.bucket, Key: key })
      );
      return {
        byteSize: result.ContentLength ?? 0,
        contentType: result.ContentType ?? null,
      };
    } catch (err) {
      // A missing object is an answer, not a failure: the confirm step asks
      // "is it there?" and deserves null rather than an exception. Anything
      // else (denied, network, throttled) must NOT be read as "absent", or a
      // transient AWS error would look like a client that never uploaded.
      if (isNotFound(err)) return null;
      throw err;
    }
  }

  async remove(key: string): Promise<void> {
    // S3's DeleteObject is already idempotent — deleting a missing key returns
    // success — so no existence check is needed here (§34).
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.bucket, Key: key })
    );
  }
}

/** True for the 404-ish shapes S3 and its HTTP layer raise for a missing key. */
function isNotFound(err: unknown): boolean {
  const e = err as { name?: string; $metadata?: { httpStatusCode?: number } };
  return (
    e?.name === 'NotFound' ||
    e?.name === 'NoSuchKey' ||
    e?.$metadata?.httpStatusCode === 404
  );
}

let cached: ObjectStore | null = null;

/**
 * The store this process should use.
 *
 * Cached because an S3Client owns a connection pool; building one per request
 * would leak sockets. Defaults to the unconfigured store so a deployment with
 * no bucket still boots and serves text posts.
 */
export function createObjectStore(): ObjectStore {
  if (cached) return cached;
  cached = s3Configured ? new S3ObjectStore() : new UnconfiguredObjectStore();
  return cached;
}

/** Reset the cache. Tests only — production builds it once per process. */
export function resetObjectStore(): void {
  cached = null;
}
