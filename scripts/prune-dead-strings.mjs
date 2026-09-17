/**
 * Remove string resources nothing references.
 *
 * A throwaway tool, kept out of the app: it reads every `.kt`/`.xml` under
 * `app/src`, collects the resource names that appear anywhere in them, and
 * deletes the `<string>`/`<plurals>` entries from `values/strings.xml` whose
 * names appear nowhere.
 *
 * It exists because the Good Post redesign removed whole features — signup by
 * phone or email, OTP, photo editing, polls, analytics, reports, the message
 * inbox, reactions and views — and every one of them left its strings behind.
 * A missing string is a compile error; an unused one is invisible, which is why
 * there were 179 of them.
 *
 * Safety: a name is only removed when it is absent from EVERY `.kt` and `.xml`
 * file, so a string read through `R.string.x` or `@string/x` in a layout is
 * kept. Run it with `--dry` first: same scan, no writes.
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';

const STRINGS = 'app/src/main/res/values/strings.xml';
const SRC = 'app/src';
const dry = process.argv.includes('--dry');

const xml = readFileSync(STRINGS, 'utf8');

/** Every Good Post resource name declared in the file. */
const declared = [...xml.matchAll(/<(?:string|plurals) name="(goodpost[a-z0-9_]*)"/g)].map(
  (m) => m[1]
);

/** Every source file that could reference one. */
const sources = [];
(function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = `${dir}/${entry.name}`;
    if (entry.isDirectory()) walk(path);
    else if (/\.(kt|xml)$/.test(entry.name)) sources.push(path);
  }
})(SRC);

const corpus = sources
  .filter((f) => !f.endsWith('values/strings.xml'))
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n');

const dead = declared.filter((name) => !corpus.includes(name));

let next = xml;
const missed = [];
for (const name of dead) {
  const before = next;
  next = next
    .replace(new RegExp(`[ \\t]*<string name="${name}"[^>]*>[\\s\\S]*?</string>\\r?\\n`, 'g'), '')
    .replace(new RegExp(`[ \\t]*<plurals name="${name}"[^>]*>[\\s\\S]*?</plurals>\\r?\\n`, 'g'), '');
  if (next === before) missed.push(name);
}

// Three blank lines where a block used to be is noise; one is a separator.
next = next.replace(/(\r?\n){3,}/g, '\n\n');

const left = [...next.matchAll(/<(?:string|plurals) name="([a-z0-9_]*)"/g)].map((m) => m[1]);
const duplicates = left.filter((name, i) => left.indexOf(name) !== i);

console.log(
  `declared ${declared.length} · unreferenced ${dead.length} · removed ${dead.length - missed.length}`
);
if (missed.length) console.log(`  pattern misses: ${missed.join(', ')}`);
if (duplicates.length) console.log(`  DUPLICATES: ${duplicates.join(', ')}`);
console.log(`  goodpost strings left: ${left.filter((n) => n.startsWith('goodpost')).length}`);

if (dry) {
  console.log('dry run: nothing written');
} else {
  writeFileSync(STRINGS, next);
  console.log(`wrote ${STRINGS}`);
}
