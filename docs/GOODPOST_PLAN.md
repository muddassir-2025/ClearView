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
shareable deep link, Discover with search + category + country + popularity
ranking (§5) — ranked by followers × recent activity, **no AI recommender**.
Android: Channels view, channel screen, Discover.

**Verified:** server typecheck clean and 108/108 tests pass (36 new), migration
003 applied to real Neon (5 tables, 15 indexes, 2 enums, 9 seeded categories),
Android 553/553 unit tests pass (25 new) and `assembleDebug` builds.

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

   **Still not actionable end to end**, and no Share button ships until it is:
   resolving that link needs a manifest `intent-filter`, a slug→id lookup on the
   backend, and routing from `MainActivity` into the Good Post tab. Shipping a
   share button that produces a dead link would be worse than not having one.
4. **`follower_count` is denormalised** on `channels` and maintained in the
   same transaction as the follow change. Discovery sorts on every search
   keystroke; a `COUNT(*)` per row would be a scan.

### M3 — Posts & media
Migration 004 (posts, post_media, links, polls). Five post types. Presigned
upload → confirm → publish; presigned download with caching headers.
Android: composer, Posts view, image/video/audio rendering, manual download.

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
