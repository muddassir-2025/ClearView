# Good Post — Implementation Plan

WhatsApp-Channels-style broadcasting inside ClearView, without touching the
existing Quran / Media / Block / Feed functionality.

This document is the plan of record. It records what Phase 1 actually found,
what the stack is, the milestone order, and **exactly what you must provide**.

---

## 0. What the repository actually looks like

Findings that shape every decision below.

| Area | Reality |
|---|---|
| Navigation | Hand-rolled: `NavigationBar` over `enum MainTab { QURAN, MEDIA, FEED, BLOCK }` in `MainActivity.kt`. **Not** Navigation-Compose. Feature screens are full-screen `Dialog(usePlatformDefaultWidth = false)`. |
| Networking | **No HTTP library at all.** 15+ files use raw `HttpURLConnection` + `org.json` + coroutines. No Retrofit, OkHttp, Moshi or kotlinx.serialization. |
| Persistence | No Room, no DataStore, no DI. `SharedPreferences` + JSON strings. |
| Background work | WorkManager is well established (`QuranWorkScheduler`, `MediaWorkScheduler`, `AudioWorkScheduler` + `*Notifier`). |
| Notifications | Channels created in `MainActivity.onCreate`. No FCM — `firebase-analytics` only. |
| Secure storage | **Nothing.** No `EncryptedSharedPreferences`. Token storage is net-new. |
| Tests | 44 unit-test files; strong convention that pure logic is unit-tested. |
| Backend | **`server/` contained only `.env.example`.** The previous backend is not in this repo. It was Express + Mongoose + zod + jsonwebtoken + firebase-admin + node-cron, plain JS. |

Two consequences worth stating plainly:

1. **The old design is being replaced, not extended.** The deleted
   `.env.example` described "ClearView Channels" on MongoDB Atlas. We keep
   every *product rule* from it (ban pepper, retention window, rate limits,
   bootstrap admin, channel cap) and change the datastore to Neon and the
   media layer to S3.
2. **"Channel" is overloaded.** The existing `/api/channels/check` +
   `blocked-channels` in `ClearViewBackendClient` means *moderation of YouTube
   channels* for the Block tab. A Good Post "channel" is a broadcast feed.
   They must never share a table, a route prefix or a name in code.
   → Good Post routes live under `/api/v1/...`; Good Post tables live in the
   `goodpost` Postgres schema. The old client is untouched.

---

## 1. Stack

| Layer | Choice | Why |
|---|---|---|
| Backend | Node 22 + TypeScript + Express 5 | Mandated by §31/§47; matches the previous backend's shape so the port is mechanical. |
| Database | Neon PostgreSQL, `pg` driver, hand-written SQL | §33/§42. No ORM: the schema is the product here (FKs, partial indexes, cascade rules), and an ORM would hide exactly the parts that need to be reviewed. |
| Migrations | Forward-only SQL + advisory-locked runner | §33 forbids startup schema mutation. |
| Media | Amazon S3, presigned URLs | §9/§43. `presigned PUT` for upload, `presigned GET` for playback. |
| Push | Firebase Cloud Messaging | §17. Same service account as Phone Auth. |
| Auth | Firebase Phone Auth → backend-issued JWT access + opaque refresh token | §2/§3. Firebase verifies the number; **our** backend owns the session, so a ban revokes access immediately (§19). |
| Admin UI | Server-rendered + vanilla TS/HTML | §31. Same backend, same repo, no second framework, no Node toolchain the dashboard can drift from. |
| Android | Existing raw `HttpURLConnection` + coroutines + `SharedPreferences` JSON cache + WorkManager | §35 forbids introducing Retrofit/Room/DI. |

---

## 2. Architecture

```
Android (ClearView)
  GoodPost tab
    → GoodPostViewModel  (coroutines, StateFlow)
      → GoodPostRepository
        → GoodPostApi          (HttpURLConnection + org.json)
        → GoodPostCache        (SharedPreferences JSON + files dir)
        → GoodPostMediaStore   (manual downloads, local reuse)
  ↓ HTTPS, only the base URL is baked in
Backend (Render)
  /api/v1/*        user + channel surfaces
  /admin/api/*     admin surface, different token audience
    → services (auth, channel, post, media, engagement, moderation)
      → pg pool ──→ Neon PostgreSQL  (schema: goodpost)
      → S3 client ──→ S3 bucket      (prefix: goodpost/)
Admin dashboard (Render, second service)
  → /admin/api/* only
```

**Data rule.** Structured data → Neon. Binary → S3, with only the object key
and metadata in Neon. Media never passes through the API process — the client
uploads to S3 directly with a presigned URL and then confirms the key to the
API, which is what keeps the API small and cheap.

---

## 3. Milestones

Each milestone ends green: typecheck, tests, `assembleDebug` for Android work.

### M0 — Scaffold ✅ done
`server/` with validated env config, pg pools + transactions, app factory,
health endpoints, migration runner, Render blueprint, README.
**Verified:** typecheck clean, 8/8 tests pass, built `dist/` boots and answers
`/health` 200, `/health/db` 503, unknown path 404.

### M1 — Identity & sessions ✅ done
- Firebase ID-token verification → `phoneHash` via HMAC pepper.
- Register / login / refresh / logout; access JWT (15 min) + rotating opaque
  refresh token stored only as a SHA-256 hash, so a database leak cannot mint
  sessions.
- **Mobile-ban enforcement at registration** (§19): a banned `phone_hash`
  cannot register, ever, regardless of new email, name, or reinstall — the
  check is server-side against `banned_identities`, not a client flag.
- OTP/verification rate limits (§3), lockout on repeated failures.
- Android: secure token storage, sign-in / register screens, the
  **unauthenticated gate on the Good Post tab only** — the rest of ClearView
  keeps working with no login.

### M2 — Channels & discovery ✅ done
`migrations/003_channels.sql` (channels, channel_admins, channel_followers,
channel_blocks, channel_categories).
Create / edit / category / country, follow / unfollow / mute / block,
shareable deep link **resolved end to end**, Discover with search + category +
country + popularity ranking (§5) — ranked by followers × recent activity,
**no AI recommender**.
Android: Channels view, channel screen, Discover, Share action, deep-link entry.

**Verified:** server typecheck clean and 115/115 tests pass (43 new), migration
003 applied to real Neon (5 tables, 15 indexes, 2 enums, 9 seeded categories),
Android 558/558 unit tests pass (30 new) and `assembleDebug` builds. The share
link was also verified on a real device: `adb shell cmd package
query-activities` resolves `clearview://goodpost/channel/<slug>` to exactly one
activity, and cold-starting that intent opens Good Post with the gate in front of
it — the link survives sign-in and opens the channel afterwards.

**Four deviations from the line above, each deliberate:**

1. **No `channel_notifications` table yet.** Mute/unmute is per-follow state
   (§17) and lives on `channel_followers.notifications_enabled`, which is the
   only thing mute actually changes. A delivery/inbox table is only meaningful
   once FCM exists (M8); creating it now would be an unexercised table.
2. **Channel icon upload is deferred to M3.** The `icon_object_key` column
   exists and stays null: an icon is an S3 operation, S3 is not configured yet
   by your instruction, and the API shape does not change when it lands.
3. **`shareLink` is an app deep link (`clearview://goodpost/channel/<slug>`),
   not an `https://` URL.** An https share link requires a public,
   unauthenticated page, and §5.3 of this document records public read-only
   channel pages as an open decision. Handing the client an app link now beats
   publishing a URL that 404s.

   **Now actionable end to end**, which is what the M2 completion pass added:
   `GET /api/v1/channels/by-slug/:slug` (reusing one query with `getChannel`, so a
   shared link and a tapped channel cannot answer with different shapes), a
   `VIEW` intent-filter on `LauncherActivity`, and routing from `MainActivity`
   into the Good Post tab. The Share button ships with it — it was withheld in
   M2 precisely because a button producing a dead link is worse than no button.

   The link is held across the sign-in gate rather than dropped: tapping one
   while signed out shows the gate, and the channel opens once sign-in finishes.
4. **`follower_count` is denormalised** on `channels` and maintained in the
   same transaction as the follow change. Discovery sorts on every search
   keystroke; a `COUNT(*)` per row would be a scan.

### M3 — Posts & media ✅ done
Migration 004 (posts, post_media). Five post types. Presigned upload → confirm
→ publish; presigned download.
Android: composer, Posts view, image/video/audio rendering, manual download.

**Done so far (verified):**

- **Migration 004, applied to real Neon.** 2 tables, 3 enums, 4 indexes, 10 CHECK
  constraints, confirmed by querying Neon after applying rather than by reading
  the file back.
- **`src/media/store.ts`** — the `ObjectStore` interface, the key policy, the
  content-type allow-list, the S3 implementation and an unconfigured fallback.
  Injectable for the same reason the Firebase verifier is: the upload lifecycle
  (presign → upload → confirm → claim) is the part that can be wrong, and it must
  be testable without an AWS account.
- **`UPLOAD_CLAIM_WINDOW_MINUTES`** and **`MAX_POST_MEDIA`** added to config.

**Two deviations from the line above, both to avoid unexercised schema:**

1. **No `polls` tables.** Polls are §14 and M4's feature. Creating the tables now
   would put tables nothing writes into the database — the same reasoning that
   kept `channel_notifications` out of M2.
2. **No `links` table.** A link IS a post's content: `link_url` and `link_title`
   are columns on `posts`, and a 1:1 table for two of them would be a join that
   earns nothing. The `type` enum still distinguishes `link` from `text`.

**Completed since:**

- **`src/media/service.ts`** — the whole lifecycle: `requestMediaUpload`
  (presign), `confirmMediaUpload` (verify against the bucket, idempotent),
  `mediaDownloadUrl` (authorized, fresh, per request), `lockMediaForClaim`
  (`FOR UPDATE`, inside the publish transaction), `attachMediaToPost`, and
  `sweepAbandonedUploads` for §34.
- **`src/posts/service.ts`** — `publishPost`, `listChannelPosts`, `listFeed`,
  `updatePost`, `deletePost`. The post **type is derived** from the attached
  media or the link, never accepted from the client; media is claimed inside the
  publish transaction; and `post_count`/`last_post_at` move with the rows.
- **`src/channels/visibility.ts`** — one definition of "may this viewer read
  this channel's content", imported by BOTH the posts reads and `mediaDownloadUrl`.
  Two copies would have drifted in exactly the direction that leaves an asset
  served after its post was removed; a test caught the first version missing it.
- **`src/media/routes.ts`, `src/posts/routes.ts`** — mounted at
  `/api/v1/media`, `/api/v1/posts` and `/api/v1/channels/:channelId/posts`. The
  channel-scoped routes share the channel router's prefix and are registered
  first, so a post request is answered without the channel router's blanket
  `requireSession` running first (two session lookups per request otherwise).
- **Retention job** now runs the abandoned-upload sweep on its schedule; the
  history-window prune stays in M7.
- **`errorHandler` keeps a deliberate 5xx code.** `media_unavailable` and
  `auth_unavailable` were being flattened to `internal_error`, which threw away
  the one thing the client branches on.
- **Tests: 66 new** (`tests/posts.test.ts`), against real Postgres and a fake
  bucket. Server total **181**, Android total **570**.

**Android (M3):**

- `GoodPostPosts.kt` (models + codec, cached WITHOUT media URLs — a presigned
  link is a short-lived capability and writing one to disk presents an expired
  address as a saved asset), `GoodPostPostsCache.kt`, `GoodPostPostsRepository.kt`,
  `GoodPostMediaStore.kt` (presigned PUT, manual download to a `.part` then
  rename), `ui/GoodPostFeed.kt` (feed, post rows, composer, edit form).
- Media shows a real preview for images and honest labels for video/audio: it
  does **not** auto-play, because no player is wired to S3 media yet. Saving is a
  deliberate tap and nothing is fetched before it (§10).
- Cached lists are dropped on sign-out; **downloaded media is deliberately kept**
  (§10).

**Deployed and verified against the real bucket.** `goodpost-bucket`
(`eu-central-1`), IAM user `clearview-goodpost-s3`, policy as in
`docs/aws-s3-policy.json`. The whole lifecycle was then run through the
**deployed** service, not a fake:

| Step | Result |
|---|---|
| `POST /media/uploads` | `201`, presigned URL issued |
| `PUT` the bytes | `200` |
| `aws s3api head-object` | present, 70 bytes, `image/png` — seen from AWS, not from our own API |
| `POST /media/uploads/:id/confirm` | `200`, status `ready` |
| publish with `mediaIds` | `201`, presigned read URL returned |
| `GET` that read URL | `200`, 70 bytes, **byte-identical** to what was uploaded |

**That probe found a real bug, which is the reason to run it against the live
thing.** Confirming an asset that was never uploaded answered **`500
internal_error`** — unwordable for the client, useless for an operator.

Root cause: S3 answers a HEAD for an absent key with **403, not 404**, unless the
caller holds `s3:ListBucket` — it cannot confirm absence without list
permission. `isNotFound()` correctly refused to read 403 as "absent" (a transient
AWS error must never look like a client that never uploaded), so the error fell
through as a raw throw.

Both halves are now fixed, because either alone is wrong:

- **`isAccessDenied()`** classifies 403 by **status code, not error name** — the
  real shape is `UnknownError` with `{"name":"Unknown"}`, so a name-based check
  (the obvious first guess) would have missed the case entirely. A refused HEAD
  is now `503 media_unavailable` with a log line naming the policy, never the
  user's failure.
- **The policy grants `s3:ListBucket`** (bucket-level, this bucket only), so an
  absent key really does answer 404 and the friendly `400 media_not_uploaded`
  path is reachable instead of being dead code. Re-verified with the app's own
  credentials: the three object actions work, another bucket is denied,
  `ListAllMyBuckets` is denied, and HEAD on a missing key returns 404.

The alternative — reading every 403 as "the upload never arrived" — would have
hidden the next credential problem behind "send the file again", which is the
same misattribution that made the Firebase defect invisible.

**Tests: 69** in `tests/posts.test.ts` (3 new, pinning both error shapes).
Server total **203**.

### M1.1 — Email sign-in, and a defect found on a real device

**A sign-in defect, and how it was actually found.** Signing in with a number
registered in the Firebase console as a *test number* showed "We could not
confirm that number. Try again." after the correct code had been typed. The
number, the code and the app were all fine.

It was proved rather than guessed, with a control experiment: a genuine Firebase
phone ID token was minted through Google's own REST API for that test number,
then the **same token** was offered to two verifiers.

| | Result |
|---|---|
| Local verifier, real service account | accepted — phone `***1063` |
| Deployed `/auth/signin`, identical token | `401 invalid_id_token` |

So the **deployed** Firebase Admin credentials verify nothing, while the backend
blamed the user's token.

**After the fix was deployed, the service named its own problem.** Both a junk
token and a freshly minted genuine one answered `503 auth_unavailable`, which
that code returns only when `getFirebaseAdminApp()` itself throws — the PEM
cannot be parsed. The other meaning of the same status (the three variables being
absent) is ruled out by the earlier `401`: `invalid_id_token` was only reachable
then on the branch where all three were present.

**Found, and it was worse than the `\n` escapes.** Read from the Render API, the
stored value was **1678 characters with zero `\n` escapes**; the correct value is
**1732 with 28 of them**. The difference was not escaping — **the
`-----BEGIN PRIVATE KEY-----` header and the `-----END PRIVATE KEY-----` footer
were missing entirely**, so only the base64 body had ever been pasted. No amount
of escaping would have made that value parse.

`FIREBASE_PRIVATE_KEY` now carries the full value, and the phone flow was
re-proved on the live service **through the real Firebase token path**:

| Step | Result |
|---|---|
| `POST /auth/signin` with a genuine ID token | `404 account_not_found` — verified, and no account yet |
| `POST /auth/otp/request` (register) | `200` |
| `POST /auth/register` | `201`, device session issued |
| `GET /auth/me` with the access token | `200`, the right account |

The token was minted through Google's REST API for the console **test number**,
which is why no SMS was sent — the same number accepting `654321` is itself proof
it is registered as a test number. The temporary account and its challenge were
deleted afterwards, so the database holds no leftovers.

Two defects made that unreadable:

1. `getFirebaseAdminApp()` sat *inside* the `try` that maps every failure to
   `invalid_id_token`. A misconfigured service account — which breaks every
   sign-in identically — reported itself as the user's bad credential. Init is
   now separate and answers **503 `auth_unavailable`**, and both paths log their
   Firebase error code (never the token, never the SDK's message, which can echo
   claims back). `tests/firebase.test.ts` guards it, with an assertion that the
   injected app getter was actually called — otherwise the same 503 would arrive
   from the "not configured" branch and the test would prove nothing.
2. The client never logged the backend's reason. It now logs
   `Backend refused sign-in: invalid_id_token (http=401)`, which is the line that
   was missing.

Also fixed, because an expired verification had no way out: `otp_required` and
Firebase's `session-expired` were folded into generic wording that blamed the
number, and the only escape was "use a different number". They now say **"This
verification expired. Send a new code and try again."** and the code step has a
*Send a new code* action.

**Email sign-in (§2, second method).** `migrations/005_email_signin.sql`
(`email_verifications`), `src/email/sender.ts`, `src/auth/email.ts`,
`POST /api/v1/auth/email/otp` and `/email/signin`.

- **Sign-in only.** It never creates an account. §19 anchors the abuse identity
  on the mobile number, so an address that could register would hand every banned
  user a one-step bypass; there is no endpoint that registers from an email, and
  a test asserts that no account or session can result from the flow.
- Codes are **HMACs at rest**, compared in constant time, with an hourly send
  allowance, a per-challenge attempt limit and a TTL. Attempt counts are written
  **outside** the transaction that throws, or the limit would be unreachable —
  the same trap the phone flow documents.
- Requesting a code answers **identically whether or not the address has an
  account**. `email_not_registered` is told only after the code is verified,
  which is what proves control of the inbox.
- A delivery failure **deletes the challenge** and answers `email_unavailable`,
  rather than leaving a code the user will never receive to burn their allowance.
- The provider is behind a `Mailer` interface (§44) and called over `fetch`, so
  email added no dependency. `EMAIL_DELIVERY_MODE=console` exists for local work
  and is **refused at boot in production**, because it writes live codes to the
  log.
- Android: a Mobile/Email toggle on the entry step, its own `signInEmail` field
  (sharing the registration field would let a value cross between the two
  flows), and the code step naming whichever channel was used.

**Deployed with a real provider (Resend), end to end.** `RESEND_API_KEY`,
`EMAIL_FROM` and `EMAIL_DELIVERY_MODE=resend` are set on Render; the merged
variable set was validated against the production boot guard **before** writing
it, because a set that fails validation deploys green and then exits 1. The
probe on the live service:

| Request | Result |
|---|---|
| `POST /email/otp`, registered address | `200`, `{expiresAt, sendsRemaining: 4}` — accepted for delivery |
| `POST /email/signin`, wrong code | `401 invalid_code` |
| `POST /email/otp`, unregistered address | no different answer from the app's own logic |

**One provider limitation, stated plainly:** the Resend account has **no verified
sending domain** — the two entries in its dashboard are Android *package names*
(`com.muddassir.clearview`, `…debug`), which are not domains. On an unverified
account Resend refuses any sender outside its own sandbox, so `EMAIL_FROM` is
`ClearView <onboarding@resend.dev>` and **delivery only reaches the account
owner's own address** (`studymuddassir@gmail.com`). Any other recipient answers
`403` from Resend, which the backend reports honestly as `503 email_unavailable`.

That is enough to exercise the flow today and not enough for real users. Fixing
it needs no code change: verify a domain in Resend, then set `EMAIL_FROM` to an
address on it. The bucket steps live in `docs/AWS_S3_SETUP.md`.

**Confirmed the hard way, on a real account.** An account registered with
`osmancoder18@gmail.com` could not sign in by email, and Resend's own response
settles what it was — a delivery-account limit, not a code path:

```
403 validation_error
You can only send testing emails to your own email address (studymuddassir@gmail.com).
To send emails to other recipients, please verify a domain at resend.com/domains
```

That message also exposed a defect in the **diagnostics**: every refusal logged
the same `HTTP 403`, so a sandbox restriction, a dead key and a provider outage
were indistinguishable in the logs. The provider's short error **name** is now
logged (never its message, which quotes the recipient address — §38), pinned by
three tests.

### M1.2 — SMTP delivery, so a domain is not a prerequisite

`EMAIL_DELIVERY_MODE=smtp` sends through an authenticated mailbox — Gmail in
practice — via nodemailer. `SmtpMailer` takes its transport as a constructor
argument like every other collaborator here, so the contract is tested without
an SMTP connection: the message handed over, a failure mapped to the wordable
`email_unavailable`, the transport **rebuilt after a failure** (a cached dead
socket would break every later sign-in), and built **lazily** so an unused
mailer costs nothing.

Why this route exists: it needs **no domain to own**. The sender is a real
mailbox Google signs for, so a code reaches any recipient. What it costs is
documented rather than discovered — Google's own limits page allows **500
recipients/day** per account, with a 1–24 hour block on exceeding it — so
`docs/EMAIL_SETUP.md` states this is for development and a handful of testers,
and that a verified domain is the production answer. Switching between the two
routes is one variable; nothing else changes.

Two guards came with it, both following the existing policy that a
chosen-but-unconfigured capability should fail loudly rather than quietly:
`EMAIL_DELIVERY_MODE=smtp` now requires `SMTP_USER` and `SMTP_PASS`, and
`=resend` requires `RESEND_API_KEY`, both refused **at boot in production**
rather than degrading to `email_unavailable` for every request.

Also added: `docs/EMAIL_SETUP.md`, and `EMAIL_DELIVERY_MODE` in `render.yaml` is
`sync: false` on purpose — pinning a value there would let a blueprint sync
silently revert a working choice to one that cannot deliver.

**Verified:** server typecheck clean and **210/210** tests pass, migration 005
applied to real Neon (11 columns, 2 indexes, 3 CHECKs, confirmed by querying
Neon); Android **575/575** unit tests pass (4 new) and `assembleDebug`
builds.

**Suites were flaky first, and the cause was real:** the default 10-second hook
timeout equals the time five migrations take against PGlite, so under parallel
load a file's `beforeAll` failed and vitest reported the file's tests as
*skipped* — a green-looking suite that hides failures. `hookTimeout` is now
60s and the suite was run three times consecutively to confirm stability.

### M4 — Engagement
Reactions (aggregate only), polls (single/multi, aggregate results only),
post views with **per-user-per-post dedupe** so scrolling cannot inflate
counts, and channel analytics (owner-only).

### M5 — Moderation
Reports on user / channel / post / message; status machine
`ACTIVE → SUSPENDED → BANNED`; blocks; ban propagation that revokes every
session of the banned account in the same transaction (§19).

### M6 — Admin dashboard
Separate auth (email + phone + password, bcrypt, its own token audience), the
role/permission matrix (SUPER_ADMIN / ADMIN / MODERATOR), all ten dashboard
sections (§22), official admin messages, admin account creation by
SUPER_ADMIN only, and the append-only audit log (§29).

### M7 — Retention & cleanup
`GOODPOST_HISTORY_DAYS` window (read from config, never hard-coded),
`PURGE_GRACE_DAYS` before physical deletion, S3 orphan GC (§34), and the
explicit guarantee that **user-downloaded local media is never touched** (§10).

### M8 — Hardening, device test, docs
The §39 abuse matrix executed as real tests, end-to-end run on your connected
device, deployment docs, final report.

---

## 4. Credentials and configuration

I build against placeholders. Nothing below blocks M1–M2.

| Needed by | What | Where to get it |
|---|---|---|
| **M0 (now)** | Neon **pooled** `DATABASE_URL` | Neon → Connection string → Pooled |
| **M0 (now)** | Neon **direct** `DATABASE_URL_DIRECT` | Neon → same panel → Direct |
| M1 | `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY` | Firebase → Project settings → Service accounts → Generate private key |
| M1 | App signing **SHA-1 / SHA-256** added to Firebase | `./gradlew signingReport` |
| M3 | `AWS_REGION`, `AWS_S3_BUCKET` | S3 console |
| M3 | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | IAM user with the policy in `server/README.md` §4 |
| M6 | `ADMIN_INITIAL_EMAIL`, `ADMIN_INITIAL_MOBILE`, `ADMIN_INITIAL_PASSWORD` | You choose (12+ chars; rejected if shorter) |
| M6 | `CORS_ORIGINS` | The admin service's Render URL |
| **done** | Render URL → `PUBLIC_BASE_URL` + Android base URL | Live: `https://clearview-1neb.onrender.com` (free instance). `/health` and `/health/db` both 200; the M2 routes answer 401 unauthenticated. Set in `gradle.properties`. |

Already handled for you: `JWT_SECRET`, `JWT_REFRESH_SECRET` and
`PHONE_HASH_PEPPER` are generated by `render.yaml` — do not invent or send
them.

**Order that avoids friction:** do the two Neon URLs first (that unblocks
migrations), then Firebase (unblocks M1), then S3 (unblocks M3). You can send
them one group at a time; I'll wire each in as it arrives.

---

## 5. Decisions worth your attention

1. **Phone number is stored only as an HMAC hash.** This is what makes §19
   work while §38 holds: we can ban an identity without holding anyone's
   number. It also means a phone number is **unrecoverable** — if a user loses
   their number, "account recovery by phone" cannot mean looking it up in our
   database. `PHONE_HASH_PEPPER_VERSION` exists so the pepper can be rotated
   without invalidating existing ban history.
2. **Access token 15 min, refresh token rotating.** A ban revokes sessions in
   the same transaction, so a banned account cannot keep working for the
   token's lifetime.
3. **Anonymous reads are not allowed.** Every Good Post endpoint requires a
   session. This is stricter than WhatsApp Channels and removes an entire
   class of enumeration attack (§38). Tell me if you want public read-only
   channel pages.
4. **Admin surface is a separate service with a separate token audience.** A
   Good Post user token cannot satisfy an admin check even if an endpoint path
   is guessed (§48).
5. **`free` vs `starter` on Render.** `render.yaml` uses `free`. Three
   consequences worth knowing before choosing, all of them verified against
   Render's current documentation rather than assumed:

   - A free web service **spins down after 15 minutes** with no traffic and
     takes **about a minute** to come back. The Android client's read timeout
     is 15 seconds, so the first request after a pause reports `unreachable`
     and succeeds on the retry.
   - Free instances have **no Shell access** (SSH or dashboard) and **no
     pre-deploy command** — both are paid-only. Migrations therefore run from
     a development machine against Neon's direct URL; see `server/README.md`
     §5. An earlier version of this note said to run them "from the Shell",
     which is impossible on this plan.
   - 750 free instance hours per month, and Render's own documentation says
     **"Do not use them for production applications"**.

   `starter` (~$7/mo) removes the cold start and restores pre-deploy
   migrations.
6. **Deleting a post hard-deletes its media rows and S3 objects.** Retention
   ages old posts out server-side, but anything a user downloaded stays on
   their device forever (§10) — the server cannot and does not reach into it.

---

## 6. Risks being tracked

| Risk | Mitigation |
|---|---|
| Firebase SMS quota is small on the free tier | App Check + per-phone and per-IP send limits; the OTP door is rate-limited from day one. |
| Neon suspends idle computes | Pool-level error listener keeps the process alive; `/health` deliberately does not depend on the database. |
| `server/` and the Android app share one repo | `rootDir: server` in the Render blueprint; `server/.gitignore` excludes `.env`; no secret ever reaches Android source. |
| Presigned URLs are bearer tokens | Short TTLs (15 min upload, 60 min download), scoped to one object key, and the bucket stays private. |
| An unbounded feed breaks the Android client | Cursor pagination with `DEFAULT_PAGE_SIZE`/`MAX_PAGE_SIZE` caps from M2 onward. |
