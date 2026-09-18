# Good Post — architecture

Good Post is the channel tab inside ClearView: WhatsApp-Channels-style
broadcasting, with **no reader accounts**. A reader is never asked to sign up,
log in, verify a phone number or type a code. This document describes what the
code actually does today. It replaces an earlier milestone plan (M0–M9) whose
architecture — Firebase phone auth, email sign-in, SMS OTP, an admin dashboard —
was removed rather than patched.

## Latest pass: the shared page, My Channel, and a channel picture that saves

* **The shared page is a page.** A channel link now opens a real channel page —
  the channel's own picture (through `/c/:slug/icon`, which signs a URL when it is
  fetched rather than when the page was built), its name, handle, category and
  follower count, and its recent posts rendered by type: text with the app's own
  inline formatting, images at their real proportions, video with controls, link
  posts as cards. Two buttons, **Open in ClearView** and **Get ClearView**, and a
  short About section at the bottom. A crawler gets `og:title`, `og:description`,
  `og:image` and a canonical URL.
* **The page was also broken, not just plain.** Helmet's default CSP is
  `img-src 'self' data:`, and every image on this page is a presigned URL on the
  bucket's own domain — so the browser loaded none of them and the avatar area sat
  empty with nothing but a console violation to show for it. The policy now allows
  `https:` images and media (never `*`) and scripts from this origin only, which is
  why the page's one script is a file (`/c/app.js`) rather than an inline handler:
  it tries the deep link and falls back to the store when nothing claims it.
* **My Channel** is in the tab's menu. It opens the account's own channel as a
  PROFILE — the same page a reader sees for any channel, with the edit entry the
  owner gets on it. Offered only when the account runs exactly one channel: a
  super admin who runs five has no one channel for that label to name.
* **A new channel picture now actually appears.** Three separate faults, all of
  them fixed at the cause rather than with a delay: the picked image was never
  previewed (the form drew the initial letter and said "Uploading…" beside it), a
  second pick could have the FIRST upload's answer applied to it, and the screen
  behind the form kept the old signed URL because a save updated the lists but not
  the open channel. The form now decodes the local file for an instant preview,
  tags each upload to its own pick, and merges the saved channel back into the tab
  — dropping the replaced picture from this device's cache, since the server
  deletes the object it replaced.
* **One radius for every attachment, and a meta row that lines up.** A photo
  rounded itself at 8dp, its loader clipped again at 11dp and a clip beside it was
  10dp; the timestamp row was inset 6dp further than the text above it. `§6`'s
  "these cards look wrong without anybody being able to say why" is exactly these
  two things.
* Removed `ChannelIconField`, a fully-written channel-image component that no
  screen called — the form had grown its own copy of it.

## Previous pass: reactions, and a video card that answers the bubble

* **Reactions are real rows.** `post_reactions` (migration 006) holds one emoji
  per reader per post, so reacting again is an UPDATE and not a second row. The
  API publishes its own vocabulary (`GET /api/v1/readers/reactions` — the six
  emoji, no token needed) and the app carries **no copy of it**: a seventh emoji
  added on the server appears without an app release, and an app cannot offer one
  the API would refuse. Counts ride on the public post payload; a reader's own
  choice travels separately (`GET /api/v1/readers/me/reactions/:channel`), because
  the public read takes no credential and must not pretend to know who is asking.
* **Reacting is one gesture in two places.** Tapping a chip on a card toggles
  that emoji for that post. Picking several posts and tapping **React** in the
  selection bar puts one emoji on all of them — and takes it off all of them only
  when every selected post already carries it, which is the same rule the star
  uses. Each send is optimistic and corrected by the server's own count.
* **A video card answers the bubble now.** `PlayerView` is a real Android view
  inside the composition, and a real view is composited *above* the Compose
  content drawn around it: the bubble's selection tint never appeared on a clip,
  and a press that landed on the surface was answered by the player rather than
  by the bubble — holding a clip did nothing at all. The card now carries its own
  transparent gesture sheet and its own tint, both drawn above the player and
  below the elapsed-time readout and the speaker, which keep their own taps.
* **Reactions, starred messages and views are inside the post card**, where the
  reader looks for them, and the channel list card keeps only what a list needs.

## Previous pass: fixed media, a selection layer, and holding the media

* **Media is drawn in a fixed box.** A post's picture occupies 4:3 (cropped to
  fill) and its video 16:9 (letterboxed), whatever the file's own dimensions are
  — so the feed does not step up and down as it is scrolled, and no photo can
  push the next post off the screen. The full frame is one tap away in the
  viewer.
* **Selecting shows.** A selected post is tinted by a layer painted *over* the
  bubble (`drawWithContent`, so it covers a photo and a video poster too) and a
  tick appears in its meta row. The tint used to be drawn behind the bubble,
  where the bubble's own background hid it — selecting a post changed nothing on
  screen.
* **Holding the media selects the post.** A photo passes a long press up to the
  bubble, so the biggest part of a post to aim at is also a way to pick it up.

## Previous pass: video, device deletes, stars, notifications, tab list

* **Video plays inline.** Feed cards autoplay muted, only while mostly on screen
  and only while the app is in front, and only one card can have sound. Tapping
  opens the app's full-screen player, which has its own mute. The cache key is
  the object key, so a second open reads the disk rather than the bucket.
* **Delete means this device.** The media gallery's selection bar now offers
  "Delete from this device" to EVERY reader (memory + disk, keyed on the object
  key); the administrator's "Delete posts" sits beside it and is a separate act.
* **Stars.** `GoodPostStarred` keeps whole rows on the device, so a bookmark is
  readable after the server's retention window. Shown on the channel's
  information page, set from the post selection bar and from the media menu.
* **Notifications.** `GoodPostUpdateWorker` (WorkManager, 15 min, network
  constrained) asks only for the reader's follows, adopts timestamps on first
  sight without announcing the past, and posts one notification per update
  unless the channel is muted. Master switch lives in the Quran tab's
  notifications sheet; a per-channel switch lives on the channel's information
  page and writes to the server (it is a property of the follow).
* **The tab is the reader's list, even signed in.** A creator sees their own
  channel first and the channels they follow after it. It used to be only the
  former, which made a follow made from Explore invisible.

See also [`WHATSAPP_CHANNELS_UX.md`](WHATSAPP_CHANNELS_UX.md): the interaction
model this follows, where the code stands against it area by area, and the
design that keeps a "live" channel list cheap (§22, §24).

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
| `POST` | `/api/v1/channels/:idOrSlug/posts/views` | §9: report which of this channel's posts a reader was shown (`{ ids: [...] }`) |
| `GET` | `/health`, `/health/db` | Liveness (never touches Neon) and database readiness |

Every image URL in a response is a short-lived presigned S3 URL. An object key
is never sent to a client.

Two numbers ride on a channel payload: `followerCount` (a COUNT over
`channel_follows`) and `lastPostViews` (the newest post's own counter, zero for
a channel that has never posted). Both are read from real rows. The view report
is the ONE write on this surface and it takes no credential — it counts reads,
not readers — and it is scoped to the channel in the path, so a batch naming
another channel's post counts nothing.

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

### The shared page (§6) — served on their own paths

| Method | Path | Returns |
|---|---|---|
| `GET` | `/c/:slug` | The public channel page: identity, recent posts, both ways into the app, About ClearView |
| `GET` | `/c/:slug/icon` | A redirect to the channel's picture, signed at fetch time (or a transparent SVG when it has none) |
| `GET` | `/c/app.js` | The page's one script: try the app, fall back to the store |
| `GET` | `/api/v1/readers/reactions` | §9: the emoji this deployment offers. No token — it is the same six for everybody, and a client that is about to sign in still has to draw them |
| `GET` | `/api/v1/me/reactions/:idOrSlug` | §9: the caller's own reactions in one channel, as post → emoji |
| `PUT` | `/api/v1/me/reactions/:postId` | §9: set or change the caller's reaction on one post |
| `DELETE` | `/api/v1/me/reactions/:postId` | §9: take it off. The emoji may travel as `?emoji=`, only so the answer can carry that emoji's new count |

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

Eleven tables, and there is nothing else:

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
| `reader_devices` | FCM registration tokens, so a channel's update can be pushed (§8) |
| `post_reactions` | post + reader + one emoji, `UNIQUE (post_id, reader_id)`. The emoji is checked against the API's own list by a constraint, so drift is a failed insert and not a seventh chip nobody planned |

There is no `users`, no `notifications`, no follower graph to speak of, and no
`admin_users.phone_hash` or `posts.deleted_reason`. A post's view counter is a
column on `posts` (004), not a table of reads, because nothing asks WHO read an
update — only how many opened it. The schema is `001_init.sql` (the seven
publishing tables), `002_readers.sql` (readers and follows),
`003_creator_accounts.sql` (nullable `password_hash`, unique `firebase_uid`),
`004_post_views.sql` (the counter), `005_reader_devices.sql` (push tokens) and
`006_post_reactions.sql` — eleven tables in all. A reader who never follows
anything, reacts to nothing and installs nothing has a row in none of them.

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

### The service, field by field

| Setting | Value |
|---|---|
| Type | Web service, runtime Node |
| Root directory | `server` |
| Build command | `npm ci --include=dev && npm run build` |
| Start command | `npm start` (`node dist/index.js`) |
| Health check path | `/health` |
| Node version | 22 or newer (`engines.node`) |

`--include=dev` is load-bearing rather than decoration: `typescript` and the
`@types/*` packages are devDependencies, and a production-mode `npm ci` omits
them, so the build fails with a wall of TS7016 errors rather than a missing
compiler.

### Environment variables, exactly as the code reads them

Required — the process refuses to boot without them:

| Variable | Value |
|---|---|
| `DATABASE_URL` | Neon pooled connection string (host contains `-pooler`) |
| `JWT_SECRET` | 32+ random characters |
| `GOODPOST_HASH_PEPPER` | 32+ random characters |
| `SUPER_ADMIN_EMAIL` | the one account that can create channels |
| `SUPER_ADMIN_PASSWORD_HASH` | its bcrypt hash (or `SUPER_ADMIN_PASSWORD`, hashed at boot) |

Needed for the product to be whole:

| Variable | Value |
|---|---|
| `PUBLIC_BASE_URL` | the service's own `https://<name>.onrender.com` |
| `FIREBASE_PROJECT_ID` | `clearview-28413` — without it follows answer `auth_unavailable` |
| `NODE_ENV` | `production` |
| `DATABASE_URL_DIRECT` | Neon direct string; read only by the migration runner |
| `SUPER_ADMIN_DISPLAY_NAME` | `Good Post Admin` |
| `ADMIN_CONTACT_EMAIL` | public support address the app may show |
| `POST_RETENTION_DAYS` | `30` (§14) |
| `ADMIN_MIN_PASSWORD_LENGTH` | `8` — must match `adminMinPasswordLength` in `gradle.properties` |

For image and video posts (all four, server-side only — they never reach the
app):

| Variable | Value |
|---|---|
| `AWS_S3_BUCKET` | the bucket name |
| `AWS_ACCESS_KEY_ID` | key restricted to that bucket |
| `AWS_SECRET_ACCESS_KEY` | its secret |
| `AWS_REGION` | `auto` for Cloudflare R2, the real region for Amazon S3 |
| `AWS_ENDPOINT_URL` | R2: `https://<account-id>.r2.cloudflarestorage.com`. Empty for Amazon S3 |

**Do not set `PORT`** — Render injects it. `CORS_ORIGINS` stays empty: the
Android client sends no `Origin` header, and an empty allow-list refuses browsers
outright, which is the correct posture for an app-only API.

`GOODPOST_HASH_PEPPER` is not decoration either. It peppers the irreversible hash
of a client IP in the audit log, so the log holds no raw address.

Point an uptime monitor at `GET /health` every few minutes. That path reads
nothing and writes nothing — it does not touch Neon, deliberately, so a monitor
cannot be the thing that restarts a healthy process — but it does keep a free
instance from spinning down, which is worth more than the cold start it avoids:
the Android client's read timeout is 15 seconds and a free instance's cold start
is about a minute, so the first request after a quiet period fails as
`unreachable` and only succeeds on the retry.
