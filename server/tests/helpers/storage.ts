import type { ObjectStore, PresignedUpload, StoredObject } from '../../src/media/store.js';

/**
 * An in-memory stand-in for S3.
 *
 * The upload lifecycle — presign → upload → confirm → claim — is the part of
 * M3 that can be wrong, and every rule that matters in it is OURS, not AWS's:
 * that a client cannot choose its own object key, that a publish refuses media
 * which was never confirmed, that one upload cannot be claimed twice. Testing
 * those against a real bucket would need credentials, so they would be tested
 * rarely or not at all. This fake makes them testable on every run.
 *
 * What it deliberately does NOT do is enforce anything. The signed
 * `ContentLength`/`ContentType` that real S3 validates are simulated by
 * [put] being told what was uploaded, so a test that needs a mismatch can
 * simply say so.
 */
export class FakeObjectStore implements ObjectStore {
  readonly configured = true;

  private readonly objects = new Map<string, StoredObject>();

  /** Every upload URL issued, in order, so a test can assert the key. */
  readonly issued: { key: string; contentType: string; byteSize: number }[] = [];

  /** Keys removed, in order, so the §34 cleanup can be asserted. */
  readonly removed: string[] = [];

  async presignUpload(input: {
    key: string;
    contentType: string;
    byteSize: number;
  }): Promise<PresignedUpload> {
    this.issued.push({ ...input });
    return {
      url: `https://fake-bucket.test/${input.key}?X-Amz-Signature=fake`,
      headers: { 'Content-Type': input.contentType },
      expiresInSeconds: 900,
    };
  }

  async presignDownload(key: string): Promise<string> {
    return `https://fake-bucket.test/${key}?X-Amz-Signature=fake-read`;
  }

  async head(key: string): Promise<StoredObject | null> {
    return this.objects.get(key) ?? null;
  }

  async remove(key: string): Promise<void> {
    this.removed.push(key);
    this.objects.delete(key);
  }

  /** The key of the most recently issued upload URL. */
  lastIssuedKey(): string {
    const last = this.issued[this.issued.length - 1];
    if (!last) throw new Error('[fake-store] no upload URL has been issued');
    return last.key;
  }

  /**
   * Stands in for the client's PUT: it makes the most recently issued key
   * exist.
   *
   * [byteSize] defaults to what was declared, because that is what real S3
   * enforces — the signed `ContentLength` means a client cannot upload a
   * different size than it asked to. Passing a different value is how a test
   * simulates the one way that could be bypassed.
   */
  put(contentType?: string, byteSize?: number): string {
    const key = this.lastIssuedKey();
    const issued = this.issued[this.issued.length - 1];
    this.objects.set(key, {
      contentType: contentType ?? issued?.contentType ?? 'application/octet-stream',
      byteSize: byteSize ?? issued?.byteSize ?? 0,
    });
    return key;
  }

  /** Whether an object currently exists. */
  has(key: string): boolean {
    return this.objects.has(key);
  }

  /** Forget everything, for a test that resets between cases. */
  reset(): void {
    this.objects.clear();
    this.issued.length = 0;
    this.removed.length = 0;
  }
}
