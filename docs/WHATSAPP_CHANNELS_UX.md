# WhatsApp Channels as a reference, and what "realtime" costs here

Two questions, one document:

1. **What interaction model are we following, and where does GoodPost stand
   against it?** (§22)
2. **What is the cheapest architecture that still feels live?** (§24)

Nothing here claims access to WhatsApp's source, assets or branding. The
reference is the *observable interaction model* of WhatsApp Channels — what a
person sees and does — re-implemented with our own UI and our own backend. The
intended product stays what §22 defines: **WhatsApp Channels + lightweight
community, inside ClearView.**

---

## 1. The model, area by area

Status is against the code in this repository, not against the plan.

| Area | WhatsApp's observable behaviour | GoodPost today | Gap / decision |
|---|---|---|---|
| **Channel home / list** | The Channels tab lists **only the channels you follow**, newest update first, each row: avatar, name, latest update preview, time, and an unread count under the time. | Followed-only list (`GET /api/v1/me/following`); row = avatar + name + preview + time, with the **unread badge in the right column under the time** (`WaChannelRow(unreadCount = …)`) | **Done.** The catalogue lives in Explore, not on the tab. |
| **View counts** | WhatsApp shows a view count on a channel update. | **Inside the post card**, in the row with the post's own timestamp (`PostItem`), because the number describes that update. It used to sit on the channel LIST row, where it could only describe whatever preview the row happened to be showing — including a description, which it then described nothing about. | **Done.** |
| **Follow / unfollow** | A Follow control on discovery rows; "Following" once you do. Unfollowing is the same control. | `WaFollowAction` in Explore (kept on the row by request), and a Follow/Following control in channel info replacing the old forward arrow. Optimistic, reconciled with the server's answer. | **Done.** |
| **Discovery** | "Find channels to follow" — a searchable directory with categories. | Explore: search + category pills over `GET /api/v1/channels?q=&category=&sort=`. | **Done.** Search is now typed-ahead (250 ms debounce, stale answers dropped). |
| **Channel sharing** | "Share channel" produces a link a recipient can open. | `shareLink` is a real `https://<host>/c/<slug>` page the backend renders (`public/share.ts`), plus an app deep link. Android uses the system share sheet. | **Done.** Needs `PUBLIC_BASE_URL` set to the deployed host, or the link points nowhere. |
| **Unread indicators** | A count on the channel row; opening the channel clears it. | `unreadCount` from the server, badge on the row, cleared by `POST /me/following/:id/read` when the channel is opened. | **Done.** |
| **Notifications** | New updates from followed channels arrive as notifications, respecting mute; nothing arrives twice. | **Not built.** In-app unread badges only. | **Remaining — see §3 tier 2.** Needs FCM (free) + a device-token table + one `unread_count` increment per follower. Mute flag already exists in the DB. |
| **Feed order** | Oldest at the top, newest at the bottom; the screen opens at the newest; scrolling up reads history. | The feed reverses the server's newest-first page for display, opens scrolled to the newest update, follows a new update **only if the reader is already near the bottom**, and puts "Load older updates" at the top (`GoodPostFeed.kt`). | **Done.** |
| **Post interaction** | Long-press a post: Copy, Forward, React, Select. | Long press selects posts; the selection bar offers Copy and Delete (admins). | **Partly.** Copy works. Favorite / React / Translate are on your §9 list and are **not built**. |
| **Reactions** | One emoji per person per update, counted, never a thread. | **Not built.** | **Remaining** if wanted; the cheapest shape is one row per (post, reader, emoji) with a unique constraint, aggregated per page. |
| **Polls** | A post type: question + options, tap to vote, results visible. | **Not built.** The DB's `post_type` enum was narrowed to the four shapes the composer produces. | **Deliberately open.** A poll is a second content model (options, votes, uniqueness, result visibility). Worth doing only after notifications, because a poll nobody is told about gets no votes. |
| **Questions / responses** | Text prompts a follower can answer privately. | **Not built.** | Same reasoning as polls; belongs with follower→creator messages (§11), since both are "a message back to the channel". |
| **Follower → creator messages** | Followers can DM a channel if the channel allows it; creator reads, replies, blocks. | **Not built.** | **Remaining.** Depends on having an inbox UI and a block list. Note the cheap part: the anonymous Firebase uid is already the reader's identity, so no new auth is needed. |
| **Channel information** | Big avatar, name, label, follower count, description, created date, media strip, notification switch. | Same page (`GoodPostChannelInfo.kt`) with follower count, description, created date, media gallery, and a mute/notification row. | **Done**, except the mute row is display-only pending the notifications work. |
| **Storage management** | "Storage and data" → see what a channel's media uses, delete it locally. | **Not built.** Images are cached in memory only (`GoodPostImages.kt`, an `LruCache`), so they are re-downloaded after a process restart and there is nothing on disk to manage. | **Remaining — see §4.** Needs an on-disk media cache first; then "delete this channel's media" is a directory delete. |
| **Search** | Search inside a channel's updates. | `GoodPostScreen.ChannelSearch` + `?q=` on the posts endpoint, typed-ahead. | **Done.** |
| **Muting** | Per-channel mute; muted channels still list, without notifications. | `POST/DELETE .../following/:id/...` mute routes + `notificationsMuted` on the payload; UI row present. | **Server done**; becomes meaningful with notifications. |
| **Admin / channel management** | Post, edit, delete, channel settings, admins. | Creator sign-in (Google or email), channel creation, post create/edit/delete, bulk delete, channel edit (name, description, category, image, **admin password**), channel delete with typed confirmation, super admin can create/manage channel admins. | **Done.** |
| **Media in a post** | Images and video, downloaded to the phone on demand. | Presigned S3/R2 upload from the admin composer; signed read URLs; video opens externally. | **Uploads need bucket credentials** (`AWS_*`, see §4 of `GOODPOST.md`), and images need a disk cache to stop re-downloading. |
| **History / retention** | A channel keeps recent updates; old ones are not an archive. | `POST_RETENTION_DAYS=30`, swept every 30 minutes, so a non-follower can still discover a channel and read its last 30 days. | **Done** (§14). |

**One thing WhatsApp does that we deliberately do not:** a rating/like/"popularity"
number under a channel's name. The two numbers this product shows — `followerCount`
and `lastPostViews` — are counts of rows a person's action created (a follow, a
reported read), not a score kept about a channel.

---

## 2. What "realtime" should mean here

You are right that true realtime costs money, and the cost model decides the
design. Three possible tiers:

| Tier | Mechanism | What it costs | Verdict |
|---|---|---|---|
| **1. Foreground poll** | While the list is on screen and the app is in front, re-fetch the reader's own follows every 25 s. One indexed query returning a handful of rows. | ~2.4 requests/minute of *active* reading per device; **zero** while backgrounded. A free Render instance handles this comfortably. | **Built** — `KeepTheListCurrent` in `GoodPostScreen.kt`, gated by `repeatOnLifecycle(STARTED)` so a pocketed phone makes no requests at all, and by `refreshIfIdle()` so nothing is asked while a request is already in flight. |
| **2. Push for what matters when nobody is looking** | FCM data message per publish, fanned out to followers. Free, and it is exactly how WhatsApp itself delivers a channel update to a closed app — push, not a socket. | Free at this scale; needs a device-token table, one fan-out write per publish, and mute respected server-side. | **Next up.** This is what makes a channel feel live: the app does not need to be open. |
| **3. Sockets / SSE** | A persistent connection per device, updates streamed as they happen. | An always-on connection per reader, a process that cannot be idle, and connection churn on every network change (mobile networks hand over constantly). On a free instance this is the wrong shape: it trades a free instance for a paid one to save 20 seconds of latency. | **Deliberately rejected.** |

So the answer to "realtime, or something similar": **poll cheaply while being
watched, push when not** — never hold a connection open.

### The tiers that must NEVER be realtime

| Thing | Why not |
|---|---|
| View counts | They are a floor, not a truth (`reportedViews`, one request per page load in `GoodPostViewModel.reportViews`). A live counter would be a write per scroll and a reason to keep a socket open for a number nobody can act on. |
| Follower counts | Read on the same query as the channel list. They change a few times a day per channel; the list refresh is the update. |
| Reaction counts | If reactions are added, they arrive with the page. |
| Typing/presence | Not a feature of a channel product. |

---

## 3. Where the data lives

The split §13/§24 asks for, as implemented:

| Data | Server | Device |
|---|---|---|
| Channel text, post text | PostgreSQL, 30-day retention, keyset-paged | First page of channels, categories and each opened channel's first page of posts, in the JSON cache (`GoodPostCache.kt`); shown as "saved data" while stale |
| Media | R2/S3 object store, metadata in Postgres, object key never sent to a client | Memory cache today. **Gap:** no disk cache, so a restart re-downloads |
| Follows, unread, mute | `readers` + `channel_follows` rows, per anonymous Firebase uid | `followedIds` in memory; refreshed with the list |
| Read state | `channel_reads`-style rows (`last_read_at`) → `unreadCount` | local badge cleared on open, server-confirmed |
| View counts | `posts.view_count`, incremented per reported page | reported once per post per process (`VIEW_REPORT_LIMIT`) |

What the device is missing, in order of value per line of code:

1. **An on-disk media cache** (a directory keyed by object URL hash, plus a
   `DELETE` action for one channel's files). This is what "manage storage" in
   channel info needs to exist at all, and it is what makes images free the
   second time they are seen.
2. **Save to device** for a post's image (MediaStore insert) — §15's "download an
   image", which is a copy out of the cache rather than a second download.
3. **Offline reading** already works for the first page; extending it to the last
   N posts of a followed channel is a cache-policy change, not a new subsystem.

---

## 4. The rules that keep this cheap

Each one is enforced somewhere specific, which is why they are listed with where.

1. **Keyset pagination, never OFFSET.** A page is `WHERE (created_at, id) < (…)`
   — the work is the page, not the history (`posts/cursor.ts`).
2. **One query per page of channels**, with the preview read once per row through
   a single lateral join — not two correlated subqueries (`channels/service.ts`).
3. **Counts are computed, not maintained.** `followerCount` is a `COUNT` over the
   follows that exist. No denormalised counter to drift, no write amplification on
   every follow.
4. **One write per page load, never per post.** `reportPostViews` sends a batch of
   ids, deduplicated per process; a scroll costs nothing.
5. **Media never passes through the API.** The client PUTs straight to the bucket
   with a presigned URL, so a 100 MB video costs the server one JSON response and
   zero bytes of bandwidth.
6. **Short-lived signed read URLs** (1 h) rather than a public bucket: no
   permanent URL to leak, and no CDN bill from a hotlink.
7. **First-page-only caching on the device**, so the cache holds something a
   reader actually sees rather than pages two through five of an old search.
8. **No polling off the Home screen**, and none in the background. A feed,
   a channel page and a search are fetched when opened.
9. **Retention sweeps in one batched pass** (`PURGE_BATCH_SIZE`) rather than a
   long transaction on a cron tick.
10. **Next cheap win: `ETag` / `If-None-Match` on the follows list.** Most polls
    answer "nothing changed", and a 304 is a few hundred bytes instead of a JSON
    body. The version hash is `id:lastPostAt:unreadCount` per row, which is free
    to compute in-process. This is the cheapest bandwidth cut available and is not
    built yet.

---

## 5. Remaining work, cheapest-to-feel first

| Order | Item | Cost | Needs |
|---|---|---|---|
| 1 | Bucket credentials so uploads work | one-time setup | Cloudflare R2 bucket + 5 `AWS_*` values (§4 of `GOODPOST.md`) |
| 2 | On-disk media cache + per-channel "manage storage" | 0 (local disk) | a cache directory keyed by URL, a size/delete action |
| 3 | Save image to the device | 0 | MediaStore insert |
| 4 | `ETag` on the follows list | 0 | one hash + one header |
| 5 | Post actions: Copy / Share / Save / Favorite | 0 | a favorite row per (reader, post), or purely local |
| 6 | Notifications (FCM) | free tier | Firebase Messaging dependency, device-token table, fan-out on publish, mute respected |
| 7 | Reactions | 1 row per (post, reader) | aggregate per page, never a thread |
| 8 | Polls and questions | 2 tables | a second content shape in the composer and feed |
| 9 | Follower → creator messages | 1 table + inbox UI | block list; reuses the anonymous uid |

Nothing above needs a new service, a queue, or a websocket.
