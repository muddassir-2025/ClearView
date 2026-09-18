import type { PushMessage, PushResult, PushSender } from '../../src/notifications/fcm.js';

/**
 * A push sender that records what it was asked to send.
 *
 * Push delivery is the one part of this feature that cannot be exercised end to
 * end in a test — it needs a Firebase project and a real phone — and it is also
 * the part with the least of OUR logic in it. So the FCM client is faked and
 * everything upstream of it is real: the SQL that chooses who is notified, the
 * mute filter, the payload the client will parse, and the cleanup of a token
 * Firebase says is gone.
 *
 * [waitFor] exists because the fan-out is deliberately fire-and-forget: the
 * publish route answers before it sends, so a test asserting \"the follower was
 * told\" has to wait for the send rather than assume it already happened.
 */
export class RecordingPushSender implements PushSender {
  readonly configured = true;

  /** Every message, in the order it was handed over. */
  messages: PushMessage[] = [];

  /** Tokens this sender reports as dead, so the prune path can be exercised. */
  dead: string[] = [];

  /** Set to make every send report a failure rather than a success. */
  failEverything = false;

  async send(messages: readonly PushMessage[]): Promise<PushResult> {
    this.messages.push(...messages);
    return {
      sent: this.failEverything ? 0 : messages.length,
      dead: this.dead,
      failed: this.failEverything ? messages.length : 0,
    };
  }

  reset(): void {
    this.messages = [];
    this.dead = [];
    this.failEverything = false;
  }

  tokensNotified(): string[] {
    return this.messages.map((message) => message.token);
  }

  /** The message addressed to one token, if there is one. */
  to(token: string): PushMessage | undefined {
    return this.messages.find((message) => message.token === token);
  }

  /**
   * Wait until at least [count] messages have arrived, or give up.
   *
   * Yields to the event loop rather than sleeping a fixed amount, so a fast
   * machine does not pay for a slow one — and the timeout is generous because
   * the thing being waited for is two database round trips on PGlite.
   */
  async waitFor(count: number, timeoutMs = 2000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (this.messages.length < count && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }
}
