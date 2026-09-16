# S3 setup for Good Post media (§9, §43)

Everything here is what the code in `server/src/media/` actually does. Nothing is
included "because it is usually needed" — the statements and the steps were read
off `store.ts` and `service.ts` rather than recalled.

## What the backend does with the bucket

Exactly four object operations, on object keys only:

| Operation | Used for |
|---|---|
| `s3:PutObject` | presigned upload URL the Android client PUTs to |
| `s3:HeadObject` | `confirm` — proves the object exists and matches the recorded size/type before a post can claim it |
| `s3:GetObject` | presigned read URL |
| `s3:DeleteObject` | post deletion and the abandoned-upload sweep (§34) |

There is **no** `ListBucket`, no bucket-level call, and no `CopyObject`. That is
why the policy in `docs/aws-s3-policy.json` grants only those four actions on
`<bucket>/*` and nothing on the bucket itself.

Presigning needs no extra permission: the signing happens in the backend, and
the eventual request is authorised by the same four actions.

## Steps

1. **Create the bucket** in `eu-central-1`. Same region as Neon (`eu-central-1`)
   and the Render service (`frankfurt`), so uploads and reads stay on one
   continent.
2. **Block all public access: ON.** Nothing is served publicly. Media reaches a
   client as a short-lived presigned URL, minted only after the backend has
   checked that the viewer may read the channel (§32) — a public bucket would
   bypass that check entirely.
3. **Default encryption: on** (SSE-S3 is enough).
4. **CORS: not needed yet.** The Android client is not a browser and sends no
   `Origin`. Add a CORS rule only if the M6 admin dashboard uploads directly
   through a presigned URL, and then scope it to that dashboard's exact origin —
   never `*`.
5. **Lifecycle rules: optional backstop.** The application prunes old posts and
   abandoned uploads itself; a lifecycle rule is a safety net, not the mechanism.
   Do not set an expiry shorter than `GOODPOST_HISTORY_DAYS`, or the sweep would
   find rows whose objects have already vanished.
6. **Create an IAM user** for the backend, with **programmatic access only** (no
   console login). Attach `docs/aws-s3-policy.json` inline, with
   `REPLACE-WITH-YOUR-BUCKET-NAME` substituted.
7. **Generate an access key** for that user.

## What to put where

Render → **clearview-goodpost-api** → Environment:

| Variable | Value |
|---|---|
| `AWS_REGION` | `eu-central-1` |
| `AWS_S3_BUCKET` | your bucket name |
| `AWS_ACCESS_KEY_ID` | from step 7 |
| `AWS_SECRET_ACCESS_KEY` | from step 7 |

**Do not send the secret to me, and do not paste it into a chat.** Set it in the
Render dashboard yourself. I do not need it: I can exercise the whole lifecycle —
create a channel, request an upload URL, PUT a file, confirm it, publish a post,
read it back — against the deployed service, which is a better test than any
credential on this machine would give, because it is the real thing.

Until all four are set, uploads answer `media_unavailable` and every other
feature keeps working; a text or link post needs no bucket at all. That behaviour
is asserted in `server/tests/posts.test.ts`.

## A note on the account you showed me

An **IAM console sign-in password is not usable by this backend** — it cannot
sign API requests, and it is not what S3 needs. The access key from step 7 is.
If that console password has ever been shared anywhere, rotate it; a password in
a chat log is a password someone else may have.
