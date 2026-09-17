# Good Post — architecture

Good Post is the channel tab inside ClearView: WhatsApp-Channels-style
broadcasting, with **no reader accounts**. A reader is never asked to sign up,
log in, verify a phone number or type a code. This document describes what the
code actually does today. It replaces an earlier milestone plan (M0–M9) whose
architecture — Firebase phone auth, email sign-in, SMS OTP, an admin dashboard —
was removed rather than patched.

---

## 1. The three flows

**A reader** opens the Good Post tab and is on the channel list. No signup, no
login, no code, no phone number, nothing to fill in. They can browse, search
(Explore), open a channel, read text and image posts, download an image to their
device, and follow a channel so it appears on their home list.

Following is the one thing that needs to know *who* is following, so the app
signs in to **Firebase anonymously** the first time Good Post makes a
reader-scoped call — silently, behind the tab, with no UI and nothing to enter.
The resulting uid is what a follow, a mute and a read position hang from
(`readers`, `channel_follows`). Firebase is used for identity and for nothing
else: there is no profile, no display name and no email, and every reader
surface works before that sign-in happens and after it.

**A creator** signs in with Google or with an email and password (§16) and owns
**one** channel. The first sign-in ends in "name your channel", which creates the
channel and the account that runs it in one transaction; after that they are an
ordinary channel administrator. Still no sign-up for readers — this is a
publisher's account, and reading never needs one.

There is **one** sign-in form for both kinds of administrator, and one Continue
button. The server's own credentials are tried first, because a provisioned
account's answer is final; anything else falls through to Firebase, where a
creator is signed up or signed in. The app therefore never asks anybody to
classify their own account, and a super admin who types their password into the
only form on the screen is signed in rather than told their credentials are
wrong. Two refusals stop instead of falling through — `admin_locked` (the
account exists and is switched off) and `auth_unavailable` (this deployment
cannot verify a sign-in at all) — because no Firebase attempt could answer
either. See `adminRefusalIsFinal` in the app and `signIn` in `GoodPostViewModel`.

The screen deliberately carries **no contact address**. It used to ask readers to
write to the deployment's owner for channel access, which is now what the form
above does by itself, and a private address printed on a public screen is a
liability rather than a feature. A support address belongs in a Help row in
Settings, where somebody looks for it.

**A channel administrator** can also be provisioned by the super admin, in which
case they sign in with the email and password they were given. Either way they
can publish to **one** channel — the one their account is bound to. The binding
is a column (`admin_users.channel_id`), not a UI rule, so the server refuses
every other channel with a 404.

**The super admin** is one account configured entirely from the environment. It
can create a channel together with the login that runs it, edit or delete any
channel, and nothing else.

```
Reader        Android app ──▶ Good Post public API ──▶ Neon + S3
Channel admin Admin login ──▶ admin API (channel-scoped) ──▶ their channel only
Super admin   env credentials ──▶ admin API ──▶ create/manage channels
```

Firebase appears in exactly one place in that picture — the reader's anonymous
uid — and reaching it needs no secret on the server. A Firebase ID token is
verified against Google's published signing certificates, so the backend holds
only `FIREBASE_PROJECT_ID` (public, and the same value that ships inside the
app's `google-services.json`). There is no service-account file, no Admin SDK
credential and no Firebase private key in the deployment.

---

## 2. Android

Package `com.muddassir.clearview.goodpost`. The UI was not redesigned: the
channel list, the WhatsApp-style feed, Explore's placement and the post
presentation are the ones that already existed.

| File | Role |
|---|---|
| `GoodPostViewModel.kt` | Navigation stack, cached-then-live reads, admin actions, composer state |
| `GoodPostErrors.kt` | Backend error codes → the enum the screens word |
| `data/GoodPostApi.kt` | The transport: `HttpURLConnection` + coroutines + `org.json`, no Retrofit |
| `data/GoodPostRepository.kt` | Cache-first reads, admin session, token renewal |
| `data/GoodPostModels.kt` | The public payloads, plus the codec for the offline cache |
| `data/GoodPostText.kt` | The WhatsApp-style formatting markers, and the toggle logic |
| `data/GoodPostIdentity.kt` | The silent anonymous Firebase identity (§3), and the no-Firebase build |
| `data/AdminTokenStore.kt` | Encrypted storage of the administrator session |
| `ui/*` | The screens and the shared WhatsApp-flavoured components |

**The base URL is the only backend value the app holds**, and it carries no
secret. It is `BuildConfig.GOODPOST_BASE_URL`, set from `goodPostBaseUrl` in
`gradle.properties` or `-PgoodPostBaseUrl=…`. Empty is a supported state: the tab
then says it is not set up rather than failing with a network error.

**Formatting (§17).** A post body is one string with WhatsApp's markers left in
it — `*bold*`, `_italic_`, `~strikethrough~`, ```` ```monospace``` ````. The
server never parses it, so it round-trips through storage, reload and edit
unchanged, and `parseGoodPostText` is the only thing that turns markers into
spans. Nothing is ever rendered as HTML.

**State (§20).** Channel-scoped state is cleared in one place,
`GoodPostUiState.forChannel`, which is what keeps a half-written edit from
following you into another channel. The composer is entered and left through
`startCompose` / `startEditPost` / `resetComposer`, so the editing strip cannot
outlive the edit.

---

## 3. Backend

`server/` is a separate deployable: Node 22, TypeScript, Express 5, `pg`,
`zod`, `bcryptjs`, `jsonwebtoken`, the AWS SDK. No ORM, no framework beyond
Express, no queue.

### Public API — no authentication, read-only

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/v1/channels` | Channel list, and the same endpoint serves Explore search (`?q=`, `?category=`, `?sort=`, `?cursor=`) |
| `GET` | `/api/v1/categories` | Categories the Explore filter offers |
| `GET` | `/api/v1/channels/:idOrSlug` | One channel, by uuid or share-link slug |
| `GET` | `/api/v1/channels/:idOrSlug/posts` | Its posts, newest first, keyset-paged |
| `GET` | `/api/v1/channels/:idOrSlug/media` | Its images, for the profile gallery |
| `GET` | `/api/v1/posts/:postId` | One post, with the channel it came from |
| `GET` | `/health`, `/health/db` | Liveness (never touches Neon) and database readiness |

Every image URL in a response is a short-lived presigned S3 URL. An object key
is never sent to a client.

### Reader API — a Firebase ID token, no account

These are the only routes that need to know who is asking, and the identity they
require is the anonymous one (§3, §15). Each takes `Authorization: Bearer
<firebase id token>`; the uid is read from the **verified token**, never from the
request body.

| Method | Path | Does |
|---|---|---|
| `GET` | `/api/v1/me` | The caller's own reader row, created on first sight |
| `GET` | `/api/v1/me/following` | The channels they follow, newest activity first, with unread counts |
| `POST` | `/api/v1/me/following/:idOrSlug` | Follow a channel |
| `DELETE` | `/api/v1/me/following/:idOrSlug` | Unfollow it |
| `POST` | `/api/v1/me/following/:idOrSlug/read` | Mark it read, which clears its badge |

A deployment with no `FIREBASE_PROJECT_ID` still serves the whole public read
API and answers these routes with `auth_unavailable` — a supported state rather
than a broken one, because it is exactly the product before §3.

### Admin API — bearer token

| Method | Path | Who | Does |
|---|---|---|---|
| `POST` | `/admin/api/auth/login` | anyone | Exchange email + password for an access token and a session |
| `POST` | `/admin/api/auth/firebase` | any Firebase identity | §16: exchange a Firebase ID token for a session, or answer `needsChannel`; an address Firebase has PROVEN is linked onto an administrator that already holds it |
| `POST` | `/admin/api/creator/channel` | any Firebase identity | §16: a creator's first (and only) channel, and the account that runs it |
| `POST` | `/admin/api/auth/refresh` | session | Rotate the session for a new access token |
| `POST` | `/admin/api/auth/logout` | session | Revoke the session |
| `GET` | `/admin/api/channels` | any admin | The channels this account may publish to |
| `POST` | `/admin/api/channels` | super admin | Create a channel **and** the login that runs it, in one transaction |
| `PATCH` | `/admin/api/channels/:id` | scoped admin | Rename, re-describe, recategorise, set/clear/replace the image |
| `DELETE` | `/admin/api/channels/:id` | super admin | Delete the channel with its posts, media, bucket objects and login |
| `GET` | `/admin/api/channels/:id/posts` | scoped admin | The channel's history, including while suspended |
| `POST` | `/admin/api/channels/:id/posts` | scoped admin | Publish |
| `PATCH` | `/admin/api/posts/:id` | scoped admin | Edit the text |
| `DELETE` | `/admin/api/posts/:id` | scoped admin | Soft-delete one post |
| `POST` | `/admin/api/posts/bulk-delete` | scoped admin | Soft-delete a selection atomically |
| `POST` | `/admin/api/media/uploads` | any admin | Presign an upload |
| `POST` | `/admin/api/media/uploads/:id/confirm` | any admin | Verify the object with a HEAD, and make it claimable |
| `GET` | `/admin/api/auth/me` | any admin | Who this session is, and what it may do |
| `PUT` | `/admin/api/channels/:id/icon` | scoped admin | Set the profile image from an upload |
| `POST` | `/admin/api/channels/:id/status` | super admin | Suspend or re-activate a channel |
| `GET` `POST` | `/admin/api/admins` | super admin | List administrators; create one for a channel |
| `POST` | `/admin/api/admins/:id/status` | super admin | Disable or re-enable an administrator |
| `POST` | `/admin/api/admins/:id/sessions/revoke` | super admin | End an administrator's sessions |
| `GET` | `/admin/api/audit` | super admin | The append-only audit log |
| `GET` | `/admin/api/settings` | any admin | The limits this deployment enforces |

Every one of those is permission-gated by role (`admin/permissions.ts`) and
scoped by the account's own row, so a channel administrator cannot reach any of
the super-admin rows. The Android app uses the subset it has screens for; the
rest exist because the product needs them (a super admin has to be able to
revoke a channel's login without deleting the channel) and because the audit log
should be readable by the person it records. They are authenticated endpoints,
not public ones.

Authorization is enforced server-side on every route: the channel an
administrator may act on is read from **their own row**, never from the request.
Naming somebody else's channel is a 404, not a 403 — a 403 would confirm that the
channel exists.

### Database (Neon PostgreSQL)

Five tables, and there is nothing else:

| Table | Holds |
|---|---|
| `channels` | id, slug, name, description, category, country, status, `icon_object_key`, `last_post_at`, timestamps |
| `channel_categories` | The reference list Explore filters by (seeded) |
| `posts` | id, `channel_id`, `author_id`, type, body, link columns, `created_at`, `edited_at`, `deleted_at` |
| `post_media` | id, `post_id`, `channel_id` (a channel's icon is a row here), `owner_id`, kind, `object_key`, content type, byte size, dimensions, status, position |
| `admin_users` | id, name, email, bcrypt hash (**NULL** for a creator who signs in with Firebase), `firebase_uid`, role, status, lockout counters, `channel_id` |
| `admin_sessions` | Refresh-token hashes, expiry, revocation |
| `admin_audit_logs` | Append-only record of every administrative action, with a hashed IP |
| `readers` | The anonymous uid, and when it was last seen. **No email, no name, no phone, no profile** |
| `channel_follows` | reader → channel, plus muted and last-read-at |

There is no `users`, no `post_reactions`, no `post_views`, no `notifications`,
no `device_tokens`, and no `admin_users.phone_hash` or `posts.deleted_reason`.
The schema is `server/migrations/001_init.sql` (the seven publishing tables),
`002_readers.sql` (the two reader tables) and `003_creator_accounts.sql` (which
makes `password_hash` nullable and adds the unique `firebase_uid`) — nine tables
in all. A reader who never follows anything has a row in none of them.

**One identity can be linked to one account (§16).** A sign-in is matched by
`firebase_uid`; failing that, it may be matched onto an administrator that
already holds the same address — but only when Firebase reports that address as
**verified**, which is Google's word for it. An unverified collision is refused
with `email_taken` and nothing is linked, because Firebase does not check
`admin_users` and anybody can type a configured address into a sign-up form.

That one file replaced the sixteen-file migration chain that built the schema,
because thirteen of those files created things a later one dropped. The
database was rebuilt from `001_init.sql`, so nothing depends on the old chain.

Migrations are immutable, checksummed, forward-only files, applied by
`npm run migrate` (or `migrate:prod`). `npm run db:reset` drops `schema public`
and re-applies them, which is how the rebuild above was done. See
`server/README.md` §2 for the Neon setup and the rollback story.

### Retention (§14)

`POST_RETENTION_DAYS` (default **30**) is how long a post's copy stays on the
server, so somebody who does *not* follow a channel can still read back through
its recent history before deciding. Older posts are swept by the retention job
every `RETENTION_CRON` (30 minutes), together with the unclaimed uploads and the
soft-deleted rows. Setting it to `0` means "keep everything" — the opt-out is a
zero, never a window of no length. Media already downloaded to a phone is
untouched by the sweep; that copy is the device's.

### Storage (S3)

Post images and channel images live in S3; Neon stores only an object key. The
upload is presign → PUT → confirm (a HEAD, not the client's word) → claim, all
scoped to the administrator who requested it. The app never sees AWS
credentials; it gets a URL that authorises one object, one method, for a few
minutes. Setup: `docs/AWS_S3_SETUP.md`, policy: `docs/aws-s3-policy.json`.
The bucket can be any S3-compatible service — set `AWS_ENDPOINT_URL` and the
same code talks to Cloudflare R2, whose egress is free.

### Reading a failure back (`[req]`)

Every request writes one line, and it carries the machine-readable code the
client was given:

```
[req] POST /admin/api/creator/channel 201 1157ms
[req] POST /admin/api/auth/login 401 invalid_credentials 290ms
```

This exists because of a real support case. The app showed one sentence for many
conditions, the error handler logged 5xx only, and the 4xx a client actually hits
left no trace — so "it says something went wrong" could not be told apart from a
rejected token, a name collision or a rate limit. Successful requests are logged
too, because "did it even arrive?" is the first question and silence cannot
answer it.

Nothing sensitive is in the line: no query string (a reader's search term), no
body (an administrator's password), no headers. `/health` is skipped, since
Render polls it. The app logs the same pair — status and code, never a body —
under the `GoodPostApi` tag, so a device and a deployment can be lined up by
time.

---

## 4. Deploying

Environment variables are listed in exactly two places, and they agree:
`server/render.yaml` (what to add to a fresh Render service) and
`server/.env.example` (the same for local work). `server/README.md` §5 explains
each one.

Short version:

1. Push to GitHub.
2. Render → New → **Blueprint** → pick the repo (`rootDir: server` is already in
   `render.yaml`).
3. Paste the Neon URLs and the super-admin values when Render prompts.
4. Run `npm run migrate` from your machine against `DATABASE_URL_DIRECT`
   (migrations are a paid-plan pre-deploy command; on the free plan they run
   locally). On a database that still holds the old schema, run
   `npm run db:reset` instead — it drops the schema and applies
   `001_init.sql`, which is otherwise refused as a changed migration.
5. Set `PUBLIC_BASE_URL` to the service's own `https://…onrender.com` URL.
6. Set `FIREBASE_PROJECT_ID` to `clearview-28413` (the project id inside
   `app/google-services.json`), or leave it empty to serve a read-only deployment
   whose follows do not work.
7. Set the four `AWS_*` values to turn image posts on. Until they are set every
   upload answers `media_unavailable`, which the composer says out loud.
8. Set `goodPostBaseUrl` in `gradle.properties` to the same host and rebuild.

Point an uptime monitor at `GET /health` every few minutes. That path reads
nothing and writes nothing — it does not touch Neon, deliberately, so a monitor
cannot be the thing that restarts a healthy process — but it does keep a free
instance from spinning down, which is worth more than the cold start it avoids:
the Android client's read timeout is 15 seconds and a free instance's cold start
is about a minute, so the first request after a quiet period fails as
`unreachable` and only succeeds on the retry.
