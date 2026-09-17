# Good Post — architecture

Good Post is the channel tab inside ClearView: WhatsApp-Channels-style
broadcasting, with **no reader accounts at all**. This document describes what
the code actually does today. It replaces an earlier milestone plan
(M0–M9) whose architecture — Firebase phone auth, email sign-in, OTP, follows,
reactions, polls, an admin dashboard — was removed rather than patched.

---

## 1. The three flows

**A reader** opens the Good Post tab and is on the channel list. No signup, no
login, no code, no Firebase, no phone number, no device identifier. They can
browse, search (Explore), open a channel, read text and image posts, and
download an image to their device.

**A channel administrator** signs in with an email and a password that the super
admin gave them, and can publish to **one** channel — the one their account is
bound to. The binding is a column (`admin_users.channel_id`), not a UI rule, so
the server refuses every other channel with a 404.

**The super admin** is one account configured entirely from the environment. It
can create a channel together with the login that runs it, edit or delete any
channel, and nothing else.

```
Reader        Android app ──▶ Good Post public API ──▶ Neon + S3
Channel admin Admin login ──▶ admin API (channel-scoped) ──▶ their channel only
Super admin   env credentials ──▶ admin API ──▶ create/manage channels
```

There is no Firebase anywhere, and `app/google-services.json` is gone.

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

### Admin API — bearer token

| Method | Path | Who | Does |
|---|---|---|---|
| `POST` | `/admin/api/auth/login` | anyone | Exchange email + password for an access token and a session |
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
| `admin_users` | id, name, email, bcrypt hash, role, status, lockout counters, `channel_id` |
| `admin_sessions` | Refresh-token hashes, expiry, revocation |
| `admin_audit_logs` | Append-only record of every administrative action, with a hashed IP |

There is no `users`, no `channel_followers`, no `post_reactions`, no
`post_views`, no `notifications`, no `device_tokens`, and no
`admin_users.phone_hash` or `posts.deleted_reason`. The whole schema is
`server/migrations/001_init.sql`: seven tables, and a reader has no row in any
of them.

That one file replaced the sixteen-file migration chain that built the schema,
because thirteen of those files created things a later one dropped. The
database was rebuilt from `001_init.sql`, so nothing depends on the old chain.

Migrations are immutable, checksummed, forward-only files, applied by
`npm run migrate` (or `migrate:prod`). `npm run db:reset` drops `schema public`
and re-applies them, which is how the rebuild above was done. See
`server/README.md` §2 for the Neon setup and the rollback story.

### Storage (S3)

Post images and channel images live in S3; Neon stores only an object key. The
upload is presign → PUT → confirm (a HEAD, not the client's word) → claim, all
scoped to the administrator who requested it. The app never sees AWS
credentials; it gets a URL that authorises one object, one method, for a few
minutes. Setup: `docs/AWS_S3_SETUP.md`, policy: `docs/aws-s3-policy.json`.

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
6. Set `goodPostBaseUrl` in `gradle.properties` to the same host and rebuild.
