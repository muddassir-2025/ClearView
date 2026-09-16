# S3 setup for Good Post media (§9, §43)

Everything here is what the code in `server/src/media/` actually does. Nothing is
included "because it is usually needed" — the statements and the steps were read
off `store.ts` and `service.ts` rather than recalled.

## What the backend does with the bucket

Three object operations and one bucket operation:

| Operation | Used for |
|---|---|
| `s3:PutObject` | presigned upload URL the Android client PUTs to |
| `s3:GetObject` | presigned read URL — **and** the `confirm` HEAD, because there is no `s3:HeadObject` action |
| `s3:DeleteObject` | post deletion and the abandoned-upload sweep (§34) |
| `s3:ListBucket` | bucket-level only. Not used for listing: it is what makes an absent key answer 404 instead of 403 |

No `CopyObject`, no `ListAllMyBuckets`, and nothing outside this bucket.
`docs/aws-s3-policy.json` grants exactly the above.

Presigning needs no extra permission: the signing happens in the backend, and
the eventual request is authorised by the same actions.

### Why `s3:ListBucket` is granted when nothing lists

This is a correctness requirement, and it was found by running the real thing
rather than by reading documentation. S3 answers a HEAD for a key that does not
exist with **403, not 404**, unless the caller holds `s3:ListBucket` — because
without list permission it cannot confirm to you whether the object is absent.

That matters because "the file is not there" is a *normal* answer for `confirm`,
not an error: a client that lost connectivity between the presigned PUT and the
confirm step reaches it routinely. On a policy without `s3:ListBucket` that case
is indistinguishable from a genuinely broken permission, and the deployed
service reported it as `500 internal_error` — unwordable for the client and
useless for an operator.

With the grant, the two are distinguishable:

| S3's answer | Meaning | What the API returns |
|---|---|---|
| 404 `NotFound` | the object is absent | `400 media_not_uploaded` — "send the file again" |
| 403 | the request was refused (policy, credential) | `503 media_unavailable` — a service problem, never blamed on the upload |

The exposure from list is confined to a bucket that holds only this app's own
media objects, keyed by random uuid, and the app is its only writer. A guessed
key was already readable through `GetObject`; listing adds no reach beyond it.

## Already provisioned

Verified against the account on 16 Sep 2026, so the steps below are recorded as
what was done rather than what remains:

| | |
|---|---|
| Bucket | `goodpost-bucket` |
| Region | `eu-central-1` — same as Neon and the Render service |
| Public access block | all four settings ON |
| Default encryption | `AES256`, bucket key enabled |
| Bucket policy | none (not needed; `BlockPublicPolicy` stops a bad one being added) |
| IAM user | `clearview-goodpost-s3`, programmatic only — **no console login** |
| Policy | inline `GoodPostMediaObjectsOnly` (`GoodPostObjects` + `GoodPostBucketList`) |
| Attached managed policies | **none** |

Least privilege was **tested rather than assumed**, using the application user's
own credentials:

| Permitted | | Denied | |
|---|---|---|---|
| `PutObject` | works | `s3:ListAllMyBuckets` | denied |
| `GetObject` / HEAD | works | reading a *different* bucket | denied |
| `DeleteObject` | works | anything outside `goodpost-bucket` | denied |
| `ListBucket` | works, on this bucket only | | |
| HEAD on a missing key | 404, so "absent" is readable | | |

The last row is the one that only shows up against a real bucket: with the
original policy (no `ListBucket`) the same call returned 403, which the service
had no way to read as "absent".

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
