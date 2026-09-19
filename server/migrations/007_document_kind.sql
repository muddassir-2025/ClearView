-- §21: a post can carry a document.
--
-- A channel's attachments were images and video. A PDF is the third thing an
-- administrator actually sends — a timetable, a form, a notice — and there was no
-- way to send one: the upload endpoint refused the type before the bucket ever saw
-- it. This migration is the schema half of accepting it.
--
-- Two enum labels and one column, and nothing else changes: a document is stored
-- exactly like an image (same per-kind key prefix, same presign → PUT → HEAD
-- lifecycle, same one-kind-per-post rule), so the only new thing the database has
-- to know is what to call it.
--
-- ## Why this file holds no constraints
--
-- `ALTER TYPE ... ADD VALUE` commits the new label, and PostgreSQL refuses to USE a
-- label in the transaction that added it ("unsafe use of new value"). Every
-- constraint that names `document` therefore lives in 008, in its own transaction —
-- this runner wraps each file in BEGIN/COMMIT, so the split is what makes the two
-- halves legal rather than a matter of taste.
--
-- `ADD VALUE IF NOT EXISTS` is idempotent, so a re-run against a database that
-- already has the label is a no-op rather than an error. (It is also why this file,
-- unlike the CREATE TYPE blocks in 001, needs no DO/EXCEPTION wrapper.)
--
-- Assumes PostgreSQL 12+: adding an enum value inside a transaction block was
-- rejected outright before that, and this runner always runs one migration per
-- transaction. Every deployment of this service is Neon (15+).

ALTER TYPE media_kind ADD VALUE IF NOT EXISTS 'document';

ALTER TYPE post_type ADD VALUE IF NOT EXISTS 'document';

-- The name the sender's file had, for a document to be shown by.
--
-- Nullable and, for an image or a clip, unused: the reader sees the picture, not
-- what it was called on disk. A document is the opposite — its name IS what a
-- reader recognises it by — so the upload request carries it and the constraint in
-- 008 requires it for that kind only.
--
-- 255 characters because that is the longest name every filesystem involved in the
-- path (the picker's provider, the download folder, the browser that fetches a
-- signed URL) agrees on.
ALTER TABLE post_media ADD COLUMN IF NOT EXISTS file_name text;
