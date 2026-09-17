import { buildApp } from './app.js';
import { closePool, db } from './db.js';
import { env } from './env.js';
import { ensureSuperAdmin } from './admin/bootstrap.js';
import { describeActiveLimits, rateLimitConfigFromEnv } from './http/rateLimit.js';
import { startRetentionJob } from './jobs/retention.js';

// Report the enforced limits at boot. Deployment logs are the only place an
// operator can confirm a limit is real rather than merely configured, and
// stating the reserved-but-unenforced variables prevents the two from being
// confused.
for (const line of describeActiveLimits(rateLimitConfigFromEnv())) {
  console.log(line);
}

// §17: the super administrator comes from the environment and is reconciled at
// boot, so a fresh deployment needs no manual step and a redeploy cannot lock
// the operator out. Never awaited before `listen` — a database that is briefly
// unreachable must delay provisioning, not the health check Render polls.
void ensureSuperAdmin(db)
  .then((result) => {
    if (result.created) console.log(`[admin] super administrator provisioned (${result.adminId})`);
    else if (result.reason === 'no_super_admin_configured') {
      console.warn(
        '[admin] no SUPER_ADMIN_EMAIL configured: readers can browse, but nobody can publish.'
      );
    } else if (result.reason === 'administrators_already_exist') {
      console.warn(
        '[admin] an administrator already exists, so SUPER_ADMIN_EMAIL was not applied. Sign in with the existing account.'
      );
    }
  })
  .catch((err: unknown) => {
    // Never fatal: the reader surface does not depend on an administrator
    // existing, and refusing to boot would take the whole product down over a
    // configuration detail.
    console.error('[admin] bootstrap failed:', (err as Error).message);
  });

const server = buildApp().listen(env.PORT, () => {
  console.log(`[api] listening on :${env.PORT} (${env.NODE_ENV})`);
});

if (env.RETENTION_JOB_ENABLED) {
  startRetentionJob();
}

/**
 * Graceful shutdown. Render sends SIGTERM and then waits before killing the
 * process; closing the HTTP server first lets in-flight requests finish, and
 * releasing the pool afterwards avoids leaving Neon connections to time out.
 */
let shuttingDown = false;
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[api] ${signal} received, shutting down`);
    server.close(() => {
      void closePool().finally(() => process.exit(0));
    });
    // Do not hang forever on a stuck keep-alive connection.
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
