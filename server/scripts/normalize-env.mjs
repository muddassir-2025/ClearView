#!/usr/bin/env node
/**
 * Normalize `.env` and prove the result round-trips.
 *
 * Why this exists
 * ---------------
 * `.env` is a plain text file that has repeatedly been edited by shell
 * one-liners during development, and shell quoting is silently destructive:
 *
 *     node -e "...process.env.FOO + 'bar'..."      # bash ends the string here
 *     KEY='"postgresql://user:pw@host/db"'          # ' + " stacked on each other
 *
 * `dotenv` strips ONE layer of quotes. Anything stacked underneath survives
 * into the parsed value, where it is invisible in a diff and fatal at runtime
 * — a DATABASE_URL that reads correctly in an editor still hands `pg` a
 * hostname of `base`.
 *
 * So: strip redundant quote layers, then re-parse the candidate text with the
 * very same `dotenv.parse()` the app uses and refuse to write unless every
 * value round-trips exactly.
 *
 * Exit codes: 0 = unchanged or fixed and verified, 1 = verification failed
 * (file left untouched), 2 = fatal.
 *
 * Usage:
 *   node scripts/normalize-env.mjs           # fix in place
 *   node scripts/normalize-env.mjs --check   # report only, never write
 *
 * It NEVER prints a value — only key names and classification.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const dotenv = require('dotenv');

const here = dirname(fileURLToPath(import.meta.url));
const envPath = join(here, '..', '.env');
const checkOnly = process.argv.includes('--check');

const KEY_RE = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/;

/**
 * Peel redundant quote layers off a raw `.env` value.
 *
 * Only strips when the FIRST and LAST characters are the same quote, so an
 * interior apostrophe (`it's`) or a URL that merely contains a quote is left
 * alone. Runs until the value stops changing, which is what unwraps the
 * `'"..."'` shape that two stacked edits produce.
 */
export function stripQuotes(raw) {
  let v = raw;
  for (let guard = 0; guard < 8; guard += 1) {
    if (v.length < 2) break;
    const first = v[0];
    const last = v[v.length - 1];
    if ((first === '"' || first === "'") && first === last) {
      v = v.slice(1, -1);
      continue;
    }
    break;
  }
  return v;
}

/**
 * Re-emit a value so `dotenv` reads back exactly what we intend.
 *
 * Unquoted is safest for the common case, but two shapes break it: a `#`
 * anywhere (dotenv treats the rest of the line as a comment) and leading or
 * trailing whitespace. Those get double quotes, with any interior `"` and any
 * backslash escaped — the backslash matters for any value that carries literal
 * `\n` (a PEM body in a deployment that pastes one) which must survive as two
 * characters rather than becoming a newline.
 */
export function renderValue(value) {
  const needsQuoting =
    value.includes('#') || value !== value.trim() || value.includes('\n') || value.includes('\r');
  if (!needsQuoting) return value;
  const escaped = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return `"${escaped}"`;
}

function main() {
  let text;
  try {
    text = readFileSync(envPath, 'utf8');
  } catch (err) {
    console.error(`[env] cannot read ${envPath}: ${err.code ?? err.message}`);
    process.exit(2);
  }

  const hadCrlf = text.includes('\r\n');
  const lines = text.split(/\r?\n/);

  // How the app itself currently READ this file. The test for "broken" is
  // whether that reading differs from the value we intend — NOT whether the
  // line's syntax differs. A correctly quoted `KEY="value"` parses to
  // `value` and needs no edit; churning it would bury the one line that does.
  const currentlyParsed = dotenv.parse(text);

  /** @type {Map<string,string>} */
  const intended = new Map();
  const changed = [];
  const out = [];

  for (const line of lines) {
    const m = line.match(KEY_RE);
    if (!m) {
      out.push(line);
      continue;
    }
    const key = m[1];
    const rawValue = m[2];
    const bare = stripQuotes(rawValue);
    intended.set(key, bare);

    if (currentlyParsed[key] === bare) {
      // Reads back exactly as intended — leave the line byte-for-byte alone.
      out.push(line);
      continue;
    }

    changed.push(key);
    out.push(`${key}=${renderValue(bare)}`);
  }

  if (changed.length === 0) {
    console.log('[env] already normalized — nothing to change.');
    return;
  }

  console.log(`[env] keys needing normalization (${changed.length}):`);
  for (const k of changed) console.log(`  - ${k}`);

  // Build the candidate and verify it BEFORE anything touches disk.
  const candidate = out.join(hadCrlf ? '\r\n' : '\n');
  const parsed = dotenv.parse(candidate);

  const mismatches = [];
  for (const [key, want] of intended) {
    const got = parsed[key];
    if (got !== want) mismatches.push(key);
  }

  if (mismatches.length > 0) {
    console.error('[env] REFUSING to write — these keys did not round-trip:');
    for (const k of mismatches) console.error(`  - ${k}`);
    console.error('[env] File left untouched. Values not shown.');
    process.exit(1);
  }

  if (checkOnly) {
    console.log('[env] --check only; would have fixed the keys above. Nothing written.');
    return;
  }

  writeFileSync(envPath, candidate, 'utf8');
  console.log(`[env] normalized ${changed.length} key(s); round-trip verified against dotenv.`);
}

main();
