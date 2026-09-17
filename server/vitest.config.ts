import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    // Applying the migrations to PGlite (a real Postgres compiled to WASM)
    // takes several seconds on its own — close to the default hook timeout.
    // Every suite creates its own database in `beforeAll`, so under parallel
    // load files would intermittently fail there, and vitest reports a failed
    // hook as the file's tests being SKIPPED. That is a flaky suite that also
    // hides real failures, so the budget is raised rather than the work avoided.
    hookTimeout: 60_000,
    testTimeout: 20_000,
    // Configuration is injected here rather than read from a .env file so the
    // suite is hermetic: it never depends on, and never touches, a developer's
    // real database. dotenv does not override variables that already exist, so
    // these win over anything in server/.env.
    env: {
      NODE_ENV: 'test',
      // Port 1 refuses instantly, so the /health/db failure path is asserted
      // without waiting out a connection timeout.
      DATABASE_URL: 'postgresql://postgres:postgres@127.0.0.1:1/clearview_test',
      JWT_SECRET: 'test-access-secret-padded-to-32-chars-min',
      GOODPOST_HASH_PEPPER: 'test-pepper-padded-to-32-characters-min',
      RETENTION_JOB_ENABLED: 'false',
      // SET, unlike the rest of the deployment configuration. The
      // super-administrator bootstrap names its account with this variable, and
      // its job now includes reconciling a changed `SUPER_ADMIN_PASSWORD`
      // against that account — so a suite that never sets it would never run
      // the bootstrap at all, which is exactly the path that silently ignored a
      // changed password. Fixtures still insert their own accounts and read
      // nothing from the environment: this is the one configuration value a
      // test asserts ON rather than around.
      SUPER_ADMIN_EMAIL: 'super-admin@example.test',
      // Pinned rather than inherited. The retention sweep now deletes posts
      // older than this window (§14), so a developer with a different value in
      // server/.env would see tests/retention.test.ts disagree with the
      // fixtures' dates — a failure that describes their local configuration
      // rather than the code. The window is the one setting the suite asserts
      // ON, so it is stated here and the tests read the same number.
      POST_RETENTION_DAYS: '30',
      RATE_LIMIT_MAX: '100000',
      AUTH_RATE_LIMIT_MAX: '100000',
      // Every rule needs raising here, not just the ones that existed when this
      // file was written. At the shipped default (40 writes/min/IP) an admin
      // suite that publishes and uploads exhausts the budget part way through
      // and reports 429s, which reads like a failing feature rather than a
      // working limiter. The limiter is still asserted on purpose, with small
      // windows, in tests/rate-limit.test.ts — so raising it here hides nothing.
      WRITE_RATE_LIMIT_MAX: '100000',
    },
  },
});
