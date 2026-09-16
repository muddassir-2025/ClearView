import { Router, type Request, type Response } from 'express';

/**
 * The administration dashboard (§22, §31).
 *
 * Server-rendered HTML with its own stylesheet and script, served by the SAME
 * process as the API. That is §31's requirement — "prefer reusing the existing
 * backend/API and TypeScript stack" — and it has three consequences worth
 * stating:
 *
 *  * **No second framework and no build step.** The dashboard cannot drift from
 *    the API's version, and there is no toolchain on Render that only the
 *    dashboard needs.
 *
 *  * **The browser never touches Neon or S3.** Every read and write goes
 *    through `/admin/api`, authenticated as an administrator. There is no
 *    database URL and no AWS credential anywhere in this file — the dashboard
 *    cannot be extended to bypass the authorization layer by accident, because
 *    it has no other way to reach the data.
 *
 *  * **No inline script or style, and no CDN.** helmet's default
 *    `Content-Security-Policy` (`script-src 'self'`) therefore applies
 *    unmodified, which is why the script and stylesheet are separate routes
 *    rather than a `<script>` block.
 *
 * The client keeps its ACCESS token in memory only — a reload signs it out of
 * that token, never of the session — and relies on the httpOnly session cookie
 * to refresh on load. Nothing is written to `localStorage`: a moderation
 * dashboard is exactly the place where a stored bearer token would be worth
 * stealing via any XSS, and "there is nothing to steal" is the only robust
 * version of that defence.
 */

const SHELL = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>ClearView — Good Post administration</title>
<link rel="stylesheet" href="/admin/app.css">
</head>
<body>
<div id="signin" class="signin" hidden>
  <form id="signin-form" class="card signin-card" autocomplete="on">
    <h1>Good Post administration</h1>
    <p class="muted">Sign in with an administrator email address or mobile number.</p>
    <label for="identifier">Email or mobile number</label>
    <input id="identifier" name="identifier" required autocomplete="username" spellcheck="false">
    <label for="password">Password</label>
    <input id="password" name="password" type="password" required autocomplete="current-password">
    <button type="submit" id="signin-button">Sign in</button>
    <p id="signin-error" class="error" role="alert" hidden></p>
  </form>
</div>

<div id="app" hidden>
  <header>
    <strong>ClearView administration</strong>
    <span id="who" class="muted"></span>
    <span class="spacer"></span>
    <button id="refresh-session" class="ghost" type="button" title="Rotate the access token now">Refresh session</button>
    <button id="signout" class="ghost" type="button">Sign out</button>
  </header>
  <nav id="nav"></nav>
  <main id="main">
    <p class="muted">Loading…</p>
  </main>
</div>

<dialog id="detail" class="detail">
  <div class="detail-head">
    <h2 id="detail-title"></h2>
    <button id="detail-close" class="ghost" type="button">Close</button>
  </div>
  <div id="detail-body"></div>
</dialog>

<dialog id="compose" class="detail">
  <div class="detail-head">
    <h2>New official message</h2>
    <button id="compose-close" class="ghost" type="button">Close</button>
  </div>
  <form id="compose-form">
    <label for="compose-target-kind">Send to</label>
    <select id="compose-target-kind">
      <option value="user">A user</option>
      <option value="channel">A channel</option>
    </select>
    <label for="compose-target-id">Target id</label>
    <input id="compose-target-id" required spellcheck="false" placeholder="Paste the id from the Users or Channels section">
    <label for="compose-subject">Subject</label>
    <input id="compose-subject" required maxlength="200">
    <label for="compose-body">Message</label>
    <textarea id="compose-body" required rows="8" maxlength="8000"></textarea>
    <p class="muted">Sent as an official platform message. It appears in the recipient's Good Post inbox.</p>
    <button type="submit">Send</button>
    <p id="compose-error" class="error" role="alert" hidden></p>
  </form>
</dialog>

<script src="/admin/app.js" defer></script>
</body>
</html>
`;

const CSS = `
:root {
  color-scheme: dark;
  --bg: #0b1220;
  --panel: #121b2e;
  --panel-2: #16213a;
  --line: #24324f;
  --text: #e8eefc;
  --muted: #90a0bf;
  --accent: #2dd4bf;
  --danger: #f87171;
  --warn: #fbbf24;
  --ok: #34d399;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font: 14px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
}
h1, h2, h3 { margin: 0 0 .5rem; font-weight: 650; }
h1 { font-size: 1.35rem; }
h2 { font-size: 1.05rem; }
h3 { font-size: .95rem; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
a { color: var(--accent); }
.muted { color: var(--muted); }
.error { color: var(--danger); }
.spacer { flex: 1; }
.hidden { display: none !important; }

.signin { display: grid; place-items: center; min-height: 100vh; padding: 1rem; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 1.25rem; }
.signin-card { width: min(420px, 100%); display: grid; gap: .4rem; }
.signin-card h1 { margin-bottom: .25rem; }

header {
  display: flex; align-items: center; gap: 1rem;
  padding: .75rem 1rem; background: var(--panel); border-bottom: 1px solid var(--line);
  position: sticky; top: 0; z-index: 5;
}
nav { display: flex; flex-wrap: wrap; gap: .35rem; padding: .6rem 1rem; border-bottom: 1px solid var(--line); }
nav button {
  background: transparent; color: var(--muted); border: 1px solid transparent;
  padding: .35rem .7rem; border-radius: 999px; cursor: pointer; font: inherit;
}
nav button:hover { color: var(--text); border-color: var(--line); }
nav button.active { background: var(--panel-2); color: var(--text); border-color: var(--accent); }
main { padding: 1rem; max-width: 1200px; margin: 0 auto; }

label { display: block; font-size: .8rem; color: var(--muted); margin-top: .6rem; }
input, select, textarea {
  width: 100%; padding: .5rem .6rem; margin-top: .2rem;
  background: var(--bg); color: var(--text);
  border: 1px solid var(--line); border-radius: 8px; font: inherit;
}
input:focus, select:focus, textarea:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
button {
  margin-top: .8rem; padding: .5rem .9rem; border-radius: 8px; border: 1px solid var(--accent);
  background: var(--accent); color: #04241f; font: inherit; font-weight: 600; cursor: pointer;
}
button.ghost { background: transparent; color: var(--text); border-color: var(--line); font-weight: 500; }
button.danger { background: transparent; color: var(--danger); border-color: var(--danger); }
button.ghost:hover, .detail-head button { margin: 0; }
button:disabled { opacity: .5; cursor: not-allowed; }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: .75rem; }
.stat { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: .9rem; }
.stat .value { font-size: 1.6rem; font-weight: 700; }
.stat .label { color: var(--muted); font-size: .8rem; }
.stat.warn { border-color: var(--warn); }
.stat.bad { border-color: var(--danger); }

.toolbar { display: flex; flex-wrap: wrap; gap: .5rem; align-items: flex-end; margin: 1rem 0; }
.toolbar input, .toolbar select { width: auto; min-width: 190px; margin: 0; }
.toolbar button { margin: 0; }

table { width: 100%; border-collapse: collapse; background: var(--panel); border-radius: 12px; overflow: hidden; }
th, td { text-align: left; padding: .55rem .6rem; border-bottom: 1px solid var(--line); vertical-align: top; }
th { color: var(--muted); font-size: .78rem; text-transform: uppercase; letter-spacing: .04em; }
tbody tr:hover { background: var(--panel-2); }
tbody tr { cursor: pointer; }
td.actions { cursor: default; white-space: nowrap; }
td.actions button { margin: 0 .25rem 0 0; padding: .25rem .5rem; font-size: .8rem; }

.badge { display: inline-block; padding: .1rem .45rem; border-radius: 999px; font-size: .75rem; border: 1px solid var(--line); }
.badge.active, .badge.success { color: var(--ok); border-color: var(--ok); }
.badge.suspended, .badge.reviewing { color: var(--warn); border-color: var(--warn); }
.badge.banned, .badge.denied, .badge.open, .badge.failed { color: var(--danger); border-color: var(--danger); }
.badge.dismissed, .badge.resolved { color: var(--muted); }

pre { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: .6rem; overflow: auto; }

.detail {
  border: 1px solid var(--line); border-radius: 14px; background: var(--panel); color: var(--text);
  width: min(760px, 92vw); max-height: 88vh; padding: 1rem;
}
.detail::backdrop { background: rgba(0,0,0,.6); }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.detail-body section { margin-top: 1rem; }
dt { color: var(--muted); font-size: .78rem; margin-top: .5rem; }
dd { margin: 0; }
`;

/**
 * The client script.
 *
 * Written with string concatenation rather than template literals throughout:
 * this file is embedded in a TypeScript template literal, and every backtick
 * inside would need escaping — the kind of escaping that breaks silently the
 * first time someone edits it. Concatenation has no such trap.
 */
const SCRIPT = String.raw`
'use strict';

var state = { token: null, csrf: null, role: null, permissions: [], section: 'overview' };

function cookieValue(name) {
  var parts = document.cookie ? document.cookie.split(';') : [];
  for (var i = 0; i < parts.length; i += 1) {
    var p = parts[i].trim();
    if (p.indexOf(name + '=') === 0) return p.slice(name.length + 1);
  }
  return '';
}

function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function el(id) { return document.getElementById(id); }

function can(permission) {
  // A CLIENT-SIDE convenience only. Every action is re-checked by the API; this
  // exists so a moderator is not shown buttons that would always fail.
  return state.permissions.indexOf(permission) !== -1;
}

async function api(path, options) {
  var opts = options || {};
  var headers = { 'Content-Type': 'application/json' };
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  if (state.csrf) headers['X-CSRF-Token'] = state.csrf;
  var response = await fetch('/admin/api' + path, {
    method: opts.method || 'GET',
    headers: headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    credentials: 'same-origin'
  });
  var text = await response.text();
  var payload = text ? JSON.parse(text) : {};
  if (!response.ok) {
    var error = new Error(payload.error || ('http_' + response.status));
    error.status = response.status;
    error.payload = payload;
    throw error;
  }
  return payload;
}

function showSignin(message) {
  el('app').hidden = true;
  el('signin').hidden = false;
  var box = el('signin-error');
  if (message) { box.textContent = message; box.hidden = false; } else { box.hidden = true; }
}

function showApp(admin, permissions) {
  state.role = admin ? admin.role : state.role;
  state.permissions = permissions || state.permissions;
  el('signin').hidden = true;
  el('app').hidden = false;
  if (admin) el('who').textContent = admin.displayName + ' — ' + admin.role.replace('_', ' ');
  renderNav();
}

var SECTIONS = [
  { id: 'overview', label: 'Overview', permission: 'overview.read' },
  { id: 'users', label: 'Users', permission: 'users.read' },
  { id: 'channels', label: 'Channels', permission: 'channels.read' },
  { id: 'posts', label: 'Posts', permission: 'posts.read' },
  { id: 'reports', label: 'Reports', permission: 'reports.read' },
  { id: 'messages', label: 'Messages', permission: 'messages.send' },
  { id: 'identities', label: 'Banned identities', permission: 'identities.read' },
  { id: 'admins', label: 'Administrators', permission: 'admins.read' },
  { id: 'audit', label: 'Audit log', permission: 'audit.read' },
  { id: 'settings', label: 'Settings', permission: 'settings.read' }
];

function renderNav() {
  var nav = el('nav');
  nav.innerHTML = '';
  SECTIONS.forEach(function (section) {
    if (!can(section.permission)) return;
    var button = document.createElement('button');
    button.type = 'button';
    button.textContent = section.label;
    button.className = state.section === section.id ? 'active' : '';
    button.addEventListener('click', function () { go(section.id); });
    nav.appendChild(button);
  });
}

function go(section) {
  state.section = section;
  renderNav();
  var render = RENDERERS[section];
  if (!render) { el('main').innerHTML = '<p class="muted">Coming soon.</p>'; return; }
  render();
}

function failure(error) {
  if (error.status === 401) {
    state.token = null;
    showSignin('Your session expired. Sign in again.');
    return;
  }
  el('main').innerHTML = '<div class="card"><h2>Request failed</h2><p class="error">'
    + escapeHtml(error.message) + '</p></div>';
}

function stats(counts) {
  var fields = [
    ['users', 'Users'], ['usersSuspended', 'Suspended users'], ['usersBanned', 'Banned users'],
    ['channels', 'Channels'], ['channelsSuspended', 'Suspended channels'],
    ['posts', 'Posts'], ['postsRemoved', 'Removed posts'],
    ['openReports', 'Open reports'], ['bannedIdentities', 'Blocked mobile identities'],
    ['admins', 'Administrators'], ['liveAdminSessions', 'Live admin sessions'],
    ['liveUserSessions', 'Live user sessions'], ['auditRows24h', 'Audit rows (24h)']
  ];
  var html = '<div class="grid">';
  fields.forEach(function (field) {
    var emphasis = '';
    if (field[0] === 'openReports' && counts[field[0]] > 0) emphasis = ' warn';
    if (field[0] === 'auditWriteFailures' && counts[field[0]] > 0) emphasis = ' bad';
    html += '<div class="stat' + emphasis + '"><div class="value">' + escapeHtml(counts[field[0]])
      + '</div><div class="label">' + field[1] + '</div></div>';
  });
  html += '</div>';
  if (counts.auditWriteFailures > 0) {
    html += '<p class="error">The audit log failed to record ' + counts.auditWriteFailures
      + ' action(s). Actions were still applied — investigate the server log.</p>';
  }
  if (counts.oldestOpenReportAt) {
    html += '<p class="muted">Oldest unclaimed report: ' + escapeHtml(counts.oldestOpenReportAt) + '</p>';
  }
  return html;
}

function table(columns, rows, rowActions) {
  var html = '<table><thead><tr>';
  columns.forEach(function (c) { html += '<th>' + escapeHtml(c.label) + '</th>'; });
  if (rowActions) html += '<th>Actions</th>';
  html += '</tr></thead><tbody>';
  if (rows.length === 0) {
    html += '<tr><td colspan="' + (columns.length + (rowActions ? 1 : 0)) + '" class="muted">Nothing here.</td></tr>';
  }
  rows.forEach(function (row, index) {
    html += '<tr data-index="' + index + '">';
    columns.forEach(function (c) {
      var value = c.render ? c.render(row) : row[c.key];
      html += '<td>' + (c.html ? value : escapeHtml(value)) + '</td>';
    });
    if (rowActions) {
      html += '<td class="actions">';
      rowActions.forEach(function (action) {
        if (action.when && !action.when(row)) return;
        html += '<button type="button" class="' + (action.className || 'ghost') + '" data-action="'
          + action.id + '" data-index="' + index + '">' + escapeHtml(action.label) + '</button>';
      });
      html += '</td>';
    }
    html += '</tr>';
  });
  html += '</tbody></table>';
  return html;
}

function bindRows(container, rows, onRow, actions) {
  Array.prototype.forEach.call(container.querySelectorAll('tbody tr'), function (tr) {
    var index = Number(tr.getAttribute('data-index'));
    if (onRow) tr.addEventListener('click', function (event) {
      if (event.target.tagName === 'BUTTON') return;
      onRow(rows[index]);
    });
  });
  Array.prototype.forEach.call(container.querySelectorAll('button[data-action]'), function (button) {
    button.addEventListener('click', function (event) {
      event.stopPropagation();
      var index = Number(button.getAttribute('data-index'));
      var action = actions.filter(function (a) { return a.id === button.getAttribute('data-action'); })[0];
      if (action) action.run(rows[index]);
    });
  });
}

function openDetail(title, html) {
  el('detail-title').textContent = title;
  el('detail-body').innerHTML = html;
  el('detail').showModal();
}

function badge(value) {
  return '<span class="badge ' + escapeHtml(value) + '">' + escapeHtml(value) + '</span>';
}

function short(text, length) {
  if (!text) return '';
  var value = String(text);
  return value.length > (length || 70) ? value.slice(0, length || 70) + '…' : value;
}

async function promptReason(action, placeholder) {
  var reason = window.prompt(placeholder || 'Reason (recorded in the audit log)');
  if (reason === null) return null;
  if (reason.trim().length < 3) { window.alert('A reason of at least 3 characters is required.'); return null; }
  await action(reason.trim());
  return true;
}

var RENDERERS = {};

RENDERERS.overview = async function () {
  try {
    var data = await api('/overview');
    el('main').innerHTML = '<h2>Overview</h2>' + stats(data.counts);
  } catch (error) { failure(error); }
};

RENDERERS.users = async function () {
  el('main').innerHTML = '<h2>Users</h2>'
    + '<div class="toolbar">'
    + '<input id="user-q" placeholder="Name or email">'
    + '<select id="user-status"><option value="">Any status</option><option>active</option>'
    + '<option>suspended</option><option>banned</option></select>'
    + '<button type="button" id="user-search">Search</button></div>'
    + '<div id="user-list"><p class="muted">Loading…</p></div>';

  var load = async function () {
    var q = encodeURIComponent(el('user-q').value.trim());
    var status = encodeURIComponent(el('user-status').value);
    try {
      var data = await api('/users?limit=50&q=' + q + '&status=' + status);
      var columns = [
        { label: 'Name', key: 'displayName' },
        { label: 'Email', key: 'email' },
        { label: 'Status', html: true, render: function (row) { return badge(row.status); } },
        { label: 'Reports', key: 'reportCount' },
        { label: 'Channels', key: 'channelCount' },
        { label: 'Sessions', key: 'liveSessions' },
        { label: 'Mobile banned', render: function (row) { return row.phoneBanned ? 'yes' : ''; } },
        { label: 'Created', render: function (row) { return row.createdAt.slice(0, 10); } }
      ];
      var actions = [
        { id: 'suspend', label: 'Suspend', when: function (row) { return can('users.moderate') && row.status !== 'suspended'; },
          run: function (row) { promptReason(function (reason) { return api('/users/' + row.id + '/status', { method: 'POST', body: { status: 'suspended', reason: reason } }); }).then(load); } },
        { id: 'reinstate', label: 'Reinstate', when: function (row) { return can('users.moderate') && row.status !== 'active'; },
          run: function (row) { promptReason(function (reason) { return api('/users/' + row.id + '/status', { method: 'POST', body: { status: 'active', reason: reason } }); }).then(load); } },
        { id: 'ban', label: 'Ban', className: 'danger', when: function (row) { return can('users.ban') && row.status !== 'banned'; },
          run: function (row) { return promptReason(function (reason) { return api('/users/' + row.id + '/status', { method: 'POST', body: { status: 'banned', reason: reason } }); }).then(load); } },
        { id: 'revoke', label: 'Sign out', when: function (row) { return can('users.moderate') && row.liveSessions > 0; },
          run: function (row) { return api('/users/' + row.id + '/sessions/revoke', { method: 'POST' }).then(load); } }
      ];
      var list = el('user-list');
      list.innerHTML = table(columns, data.items, actions);
      bindRows(list, data.items, function (row) { userDetail(row); }, actions);
    } catch (error) { failure(error); }
  };

  el('user-search').addEventListener('click', load);
  el('user-q').addEventListener('keydown', function (event) { if (event.key === 'Enter') load(); });
  await load();
};

async function userDetail(row) {
  try {
    var data = await api('/users/' + row.id);
    var html = '<dl><dt>Id</dt><dd>' + escapeHtml(data.user.id) + '</dd>'
      + '<dt>Email</dt><dd>' + escapeHtml(data.user.email) + '</dd>'
      + '<dt>Status</dt><dd>' + badge(data.user.status) + '</dd>'
      + '<dt>Mobile identity</dt><dd>' + (data.user.hasMobileIdentity ? 'on file (hashed, not readable)' : 'none')
      + (data.user.phoneBanned ? ' — identity banned' : '') + '</dd></dl>';

    html += '<section><h3>Sessions</h3>' + table([
      { label: 'Device', key: 'deviceLabel' },
      { label: 'Created', render: function (s) { return s.createdAt.slice(0, 16); } },
      { label: 'Expires', render: function (s) { return s.expiresAt.slice(0, 16); } },
      { label: 'Revoked', render: function (s) { return s.revokedAt ? s.revokedAt.slice(0, 16) + ' (' + escapeHtml(s.revokedReason) + ')' : '—'; } }
    ], data.sessions) + '</section>';

    html += '<section><h3>Channels owned</h3>' + table([
      { label: 'Name', key: 'name' },
      { label: 'Status', html: true, render: function (c) { return badge(c.status); } },
      { label: 'Followers', key: 'followerCount' }
    ], data.ownedChannels) + '</section>';

    html += '<section><h3>Reports against</h3>' + table([
      { label: 'Type', key: 'targetType' },
      { label: 'Reason', key: 'reason' },
      { label: 'Status', html: true, render: function (r) { return badge(r.status); } },
      { label: 'Filed', render: function (r) { return r.createdAt.slice(0, 16); } }
    ], data.reportsAgainst) + '</section>';

    html += '<section><h3>Ban history</h3>' + table([
      { label: 'Reason', key: 'reason' },
      { label: 'Note', render: function (b) { return escapeHtml(short(b.note, 60)); } },
      { label: 'Banned', render: function (b) { return b.createdAt.slice(0, 16); } },
      { label: 'Lifted', render: function (b) { return b.liftedAt ? b.liftedAt.slice(0, 16) : '—'; } }
    ], data.bans) + '</section>';

    openDetail('User ' + row.displayName, html);
  } catch (error) { window.alert('Could not load the user: ' + error.message); }
}

RENDERERS.channels = async function () {
  el('main').innerHTML = '<h2>Channels</h2>'
    + '<div class="toolbar"><input id="channel-q" placeholder="Name or slug">'
    + '<select id="channel-status"><option value="">Any status</option><option>active</option>'
    + '<option>suspended</option><option>banned</option></select>'
    + '<button type="button" id="channel-search">Search</button></div><div id="channel-list"></div>';

  var load = async function () {
    try {
      var data = await api('/channels?limit=50&q=' + encodeURIComponent(el('channel-q').value.trim())
        + '&status=' + encodeURIComponent(el('channel-status').value));
      var columns = [
        { label: 'Name', key: 'name' },
        { label: 'Slug', key: 'slug' },
        { label: 'Status', html: true, render: function (row) { return badge(row.status); } },
        { label: 'Owner', key: 'ownerName' },
        { label: 'Followers', key: 'followerCount' },
        { label: 'Posts', key: 'postCount' },
        { label: 'Reports', key: 'reportCount' },
        { label: 'Category', key: 'categorySlug' }
      ];
      var actions = [
        { id: 'suspend', label: 'Suspend', when: function (row) { return can('channels.moderate') && row.status !== 'suspended'; },
          run: function (row) { promptReason(function (reason) { return api('/channels/' + row.id + '/status', { method: 'POST', body: { status: 'suspended', reason: reason } }); }).then(load); } },
        { id: 'restore', label: 'Restore', when: function (row) { return can('channels.moderate') && row.status !== 'active'; },
          run: function (row) { return api('/channels/' + row.id + '/status', { method: 'POST', body: { status: 'active' } }).then(load); } },
        { id: 'ban', label: 'Ban', className: 'danger', when: function (row) { return can('channels.moderate') && row.status !== 'banned'; },
          run: function (row) { promptReason(function (reason) { return api('/channels/' + row.id + '/status', { method: 'POST', body: { status: 'banned', reason: reason } }); }).then(load); } }
      ];
      var list = el('channel-list');
      list.innerHTML = table(columns, data.items, actions);
      bindRows(list, data.items, function (row) { channelDetail(row); }, actions);
    } catch (error) { failure(error); }
  };

  el('channel-search').addEventListener('click', load);
  el('channel-q').addEventListener('keydown', function (event) { if (event.key === 'Enter') load(); });
  await load();
};

async function channelDetail(row) {
  try {
    var data = await api('/channels/' + row.id);
    var html = '<dl><dt>Id</dt><dd>' + escapeHtml(data.channel.id) + '</dd>'
      + '<dt>Slug</dt><dd>' + escapeHtml(data.channel.slug) + '</dd>'
      + '<dt>Owner</dt><dd>' + escapeHtml(data.channel.ownerName) + ' (' + escapeHtml(data.channel.ownerId) + ')</dd>'
      + '<dt>Follower messages</dt><dd>' + (data.allowFollowerMessages ? 'enabled' : 'disabled')
      + ' — ' + data.conversations + ' conversation(s)</dd></dl>';

    html += '<section><h3>Staff</h3>' + table([
      { label: 'Name', key: 'displayName' },
      { label: 'Role', key: 'role' },
      { label: 'Since', render: function (a) { return a.createdAt.slice(0, 16); } }
    ], data.admins) + '</section>';

    html += '<section><h3>Recent posts</h3>' + table([
      { label: 'Type', key: 'type' },
      { label: 'Body', render: function (p) { return escapeHtml(short(p.body, 80)); } },
      { label: 'Reports', key: 'reportCount' },
      { label: 'Removed', render: function (p) { return p.removedAt ? 'yes' : '—'; } },
      { label: 'Created', render: function (p) { return p.createdAt.slice(0, 16); } }
    ], data.recentPosts) + '</section>';

    openDetail('Channel ' + row.name, html);
  } catch (error) { window.alert('Could not load the channel: ' + error.message); }
}

RENDERERS.posts = async function () {
  el('main').innerHTML = '<h2>Posts</h2>'
    + '<div class="toolbar"><input id="post-q" placeholder="Text contains">'
    + '<select id="post-removed"><option value="false">Live posts</option><option value="true">Removed posts</option></select>'
    + '<input id="post-channel" placeholder="Channel id (optional)" size="40">'
    + '<button type="button" id="post-search">Search</button></div><div id="post-list"></div>';

  var load = async function () {
    try {
      var data = await api('/posts?limit=50&q=' + encodeURIComponent(el('post-q').value.trim())
        + '&removed=' + encodeURIComponent(el('post-removed').value)
        + '&channelId=' + encodeURIComponent(el('post-channel').value.trim()));
      var columns = [
        { label: 'Channel', key: 'channelName' },
        { label: 'Type', key: 'type' },
        { label: 'Body', render: function (row) { return escapeHtml(short(row.body, 90)); } },
        { label: 'Reports', key: 'reportCount' },
        { label: 'Reactions', key: 'reactorCount' },
        { label: 'Viewers', key: 'viewerCount' },
        { label: 'Created', render: function (row) { return row.createdAt.slice(0, 16); } }
      ];
      var actions = [
        { id: 'remove', label: 'Remove', className: 'danger', when: function (row) { return can('posts.moderate') && !row.removedAt; },
          run: function (row) { promptReason(function (reason) { return api('/posts/' + row.id + '/remove', { method: 'POST', body: { reason: reason } }); }).then(load); } },
        { id: 'restore', label: 'Restore', when: function (row) { return can('posts.moderate') && row.removedAt; },
          run: function (row) { return api('/posts/' + row.id + '/restore', { method: 'POST' }).then(load); } }
      ];
      var list = el('post-list');
      list.innerHTML = table(columns, data.items, actions);
      bindRows(list, data.items, function (row) {
        openDetail('Post ' + row.id,
          '<pre>' + escapeHtml(row.body || '(no text)') + '</pre>'
          + '<dl><dt>Channel</dt><dd>' + escapeHtml(row.channelName) + ' (' + escapeHtml(row.channelId) + ')</dd>'
          + '<dt>Media</dt><dd>' + row.mediaCount + '</dd>'
          + (row.removedAt ? '<dt>Removed</dt><dd>' + escapeHtml(row.removedAt) + ' — ' + escapeHtml(row.removedReason) + '</dd>' : '')
          + '</dl>');
      }, actions);
    } catch (error) { failure(error); }
  };

  el('post-search').addEventListener('click', load);
  await load();
};

RENDERERS.reports = async function () {
  el('main').innerHTML = '<h2>Reports</h2>'
    + '<div class="toolbar"><select id="report-status">'
    + '<option value="">Open and in review</option><option>open</option><option>reviewing</option>'
    + '<option>resolved</option><option>dismissed</option></select>'
    + '<select id="report-type"><option value="">Any target</option><option>user</option>'
    + '<option>channel</option><option>post</option><option>message</option></select>'
    + '<button type="button" id="report-search">Load</button></div><div id="report-list"></div>';

  var load = async function () {
    try {
      var data = await api('/reports?limit=50&status=' + encodeURIComponent(el('report-status').value)
        + '&targetType=' + encodeURIComponent(el('report-type').value));
      var columns = [
        { label: 'Target', key: 'targetType' },
        { label: 'Reason', key: 'reason' },
        { label: 'Reporter', key: 'reporterName' },
        { label: 'Details', render: function (row) { return escapeHtml(short(row.details, 60)); } },
        { label: 'Status', html: true, render: function (row) { return badge(row.status); } },
        { label: 'Filed', render: function (row) { return row.createdAt.slice(0, 16); } }
      ];
      var list = el('report-list');
      list.innerHTML = table(columns, data.items);
      bindRows(list, data.items, function (row) { reportDetail(row, load); });
    } catch (error) { failure(error); }
  };

  el('report-search').addEventListener('click', load);
  await load();
};

async function reportDetail(row, reload) {
  try {
    var data = await api('/reports/' + row.id);
    var report = data.report;
    var html = '<dl><dt>Target</dt><dd>' + escapeHtml(report.targetType) + ' '
      + escapeHtml(report.targetId) + '</dd>'
      + '<dt>Reason</dt><dd>' + escapeHtml(report.reason) + '</dd>'
      + '<dt>Reporter</dt><dd>' + escapeHtml(report.reporterName) + '</dd>'
      + '<dt>Details</dt><dd>' + escapeHtml(report.details || '—') + '</dd>'
      + '<dt>Status</dt><dd>' + badge(report.status) + '</dd></dl>';

    html += '<section><h3>Reported content</h3>';
    if (!data.context) {
      html += '<p class="muted">The target is no longer available (it may have been removed since).</p>';
    } else {
      var summary = data.context.summary || {};
      html += '<dl>';
      Object.keys(summary).forEach(function (key) {
        html += '<dt>' + escapeHtml(key) + '</dt><dd>' + escapeHtml(short(summary[key], 400)) + '</dd>';
      });
      html += '</dl>';
    }
    html += '</section>';

    if (can('reports.work') && (report.status === 'open' || report.status === 'reviewing')) {
      html += '<section><h3>Act on this report</h3>'
        + '<label for="report-action">Action taken</label>'
        + '<input id="report-action" placeholder="e.g. post removed, user suspended">'
        + '<label for="report-note">Internal note (optional)</label>'
        + '<textarea id="report-note" rows="3"></textarea>'
        + '<div class="toolbar">'
        + '<button type="button" id="report-claim">Mark in review</button>'
        + '<button type="button" id="report-resolve">Resolve</button>'
        + '<button type="button" class="danger" id="report-dismiss">Dismiss</button>'
        + '</div></section>';
    }

    openDetail('Report', html);

    if (el('report-claim')) el('report-claim').addEventListener('click', async function () {
      await api('/reports/' + row.id + '/resolve', { method: 'POST', body: { status: 'reviewing' } });
      el('detail').close(); reload();
    });
    if (el('report-resolve')) el('report-resolve').addEventListener('click', async function () {
      var action = el('report-action').value.trim();
      if (action.length < 3) { window.alert('Say what action was taken.'); return; }
      await api('/reports/' + row.id + '/resolve', { method: 'POST',
        body: { status: 'resolved', actionTaken: action, resolutionNote: el('report-note').value.trim() || undefined } });
      el('detail').close(); reload();
    });
    if (el('report-dismiss')) el('report-dismiss').addEventListener('click', async function () {
      await api('/reports/' + row.id + '/resolve', { method: 'POST',
        body: { status: 'dismissed', resolutionNote: el('report-note').value.trim() || undefined } });
      el('detail').close(); reload();
    });
  } catch (error) { window.alert('Could not load the report: ' + error.message); }
}

RENDERERS.messages = async function () {
  el('main').innerHTML = '<h2>Official messages</h2>'
    + '<div class="toolbar"><button type="button" id="message-new">Write a message</button>'
    + '<button type="button" id="message-reload" class="ghost">Reload</button></div><div id="message-list"></div>';

  var load = async function () {
    try {
      var data = await api('/messages?limit=50');
      var columns = [
        { label: 'Target', key: 'targetName' },
        { label: 'Kind', render: function (row) { return row.targetUserId ? 'user' : 'channel'; } },
        { label: 'Subject', key: 'subject' },
        { label: 'Sent', render: function (row) { return row.createdAt.slice(0, 16); } }
      ];
      var list = el('message-list');
      list.innerHTML = table(columns, data.items);
      bindRows(list, data.items, function (row) {
        openDetail(row.subject, '<pre>' + escapeHtml(row.body) + '</pre>');
      }, []);
    } catch (error) { failure(error); }
  };

  el('message-new').addEventListener('click', function () { el('compose').showModal(); });
  el('message-reload').addEventListener('click', load);
  await load();
};

RENDERERS.identities = async function () {
  el('main').innerHTML = '<h2>Banned mobile identities</h2>'
    + '<p class="muted">The mobile number itself is never stored — only a keyed hash. A ban is identified by the account it belongs to.</p>'
    + '<div class="toolbar"><select id="identity-filter"><option value="active">Active bans</option>'
    + '<option value="all">Include lifted</option></select>'
    + '<button type="button" id="identity-search">Load</button></div><div id="identity-list"></div>';

  var load = async function () {
    try {
      var data = await api('/identities?limit=50&status=' + encodeURIComponent(el('identity-filter').value));
      var columns = [
        { label: 'Account', render: function (row) { return escapeHtml(row.displayName || '(deleted account)'); } },
        { label: 'Email at ban time', key: 'emailNormalized' },
        { label: 'Reason', key: 'reason' },
        { label: 'Note', render: function (row) { return escapeHtml(short(row.note, 50)); } },
        { label: 'Banned', render: function (row) { return row.createdAt.slice(0, 16); } },
        { label: 'Lifted', render: function (row) { return row.liftedAt ? row.liftedAt.slice(0, 16) : '—'; } }
      ];
      var actions = [
        { id: 'lift', label: 'Lift ban', when: function (row) { return can('identities.lift') && !row.liftedAt; },
          run: async function (row) {
            if (!window.confirm('Lift this ban? The account becomes active and the number can register again.')) return;
            await api('/identities/' + row.id + '/lift', { method: 'POST' });
            load();
          } }
      ];
      var list = el('identity-list');
      list.innerHTML = table(columns, data.items, actions);
      bindRows(list, data.items, null, actions);
    } catch (error) { failure(error); }
  };

  el('identity-search').addEventListener('click', load);
  await load();
};

RENDERERS.admins = async function () {
  var html = '<h2>Administrators</h2>';
  if (can('admins.manage')) {
    html += '<div class="card"><h3>Create an administrator</h3>'
      + '<div class="grid"><div><label for="new-admin-name">Name</label><input id="new-admin-name"></div>'
      + '<div><label for="new-admin-email">Email</label><input id="new-admin-email" spellcheck="false"></div>'
      + '<div><label for="new-admin-phone">Mobile (E.164)</label><input id="new-admin-phone" placeholder="+923001234567" spellcheck="false"></div>'
      + '<div><label for="new-admin-role">Role</label><select id="new-admin-role">'
      + '<option value="moderator">moderator</option><option value="admin">admin</option>'
      + '<option value="super_admin">super_admin</option></select></div>'
      + '<div><label for="new-admin-password">Password</label><input id="new-admin-password" type="password" autocomplete="new-password"></div></div>'
      + '<button type="button" id="new-admin-create">Create</button>'
      + '<p class="muted">At least 12 characters. The new administrator can sign in immediately; nothing is emailed.</p>'
      + '</div>';
  }
  html += '<div id="admin-list"></div>';
  el('main').innerHTML = html;

  var load = async function () {
    try {
      var data = await api('/admins');
      var columns = [
        { label: 'Name', key: 'displayName' },
        { label: 'Email', key: 'email' },
        { label: 'Role', key: 'role' },
        { label: 'Status', html: true, render: function (row) { return badge(row.status); } },
        { label: 'Last signed in', render: function (row) { return row.lastLoginAt ? row.lastLoginAt.slice(0, 16) : '—'; } },
        { label: 'Created', render: function (row) { return row.createdAt.slice(0, 10); } }
      ];
      var actions = [
        { id: 'disable', label: 'Disable', className: 'danger', when: function (row) { return can('admins.manage') && row.status === 'active'; },
          run: async function (row) { await api('/admins/' + row.id + '/status', { method: 'POST', body: { status: 'disabled' } }); load(); } },
        { id: 'enable', label: 'Enable', when: function (row) { return can('admins.manage') && row.status === 'disabled'; },
          run: async function (row) { await api('/admins/' + row.id + '/status', { method: 'POST', body: { status: 'active' } }); load(); } },
        { id: 'role', label: 'Change role', when: function () { return can('admins.manage'); },
          run: async function (row) {
            var role = window.prompt('New role: super_admin, admin or moderator', row.role);
            if (!role) return;
            await api('/admins/' + row.id + '/role', { method: 'POST', body: { role: role.trim() } });
            load();
          } },
        { id: 'revoke', label: 'Sign out', when: function () { return can('admins.manage'); },
          run: async function (row) { await api('/admins/' + row.id + '/sessions/revoke', { method: 'POST' }); load(); } }
      ];
      var list = el('admin-list');
      list.innerHTML = table(columns, data.items, actions);
      bindRows(list, data.items, null, actions);
    } catch (error) { failure(error); }
  };

  if (el('new-admin-create')) el('new-admin-create').addEventListener('click', async function () {
    try {
      await api('/admins', { method: 'POST', body: {
        displayName: el('new-admin-name').value.trim(),
        email: el('new-admin-email').value.trim(),
        phone: el('new-admin-phone').value.trim(),
        role: el('new-admin-role').value,
        password: el('new-admin-password').value
      } });
      el('new-admin-name').value = ''; el('new-admin-email').value = '';
      el('new-admin-phone').value = ''; el('new-admin-password').value = '';
      load();
    } catch (error) { window.alert('Could not create the administrator: ' + error.message); }
  });

  await load();
};

RENDERERS.audit = async function () {
  el('main').innerHTML = '<h2>Audit log</h2>'
    + '<p class="muted">Append-only. Records cannot be edited or deleted, by design.</p>'
    + '<div class="toolbar"><input id="audit-limit" type="number" value="100" min="1" max="500">'
    + '<button type="button" id="audit-load">Load</button></div><div id="audit-list"></div>';

  var load = async function () {
    try {
      var data = await api('/audit?limit=' + encodeURIComponent(el('audit-limit').value));
      var columns = [
        { label: 'When', render: function (row) { return row.createdAt.slice(0, 19).replace('T', ' '); } },
        { label: 'Administrator', render: function (row) { return escapeHtml(row.adminEmail || '(removed)'); } },
        { label: 'Role', key: 'actorRole' },
        { label: 'Action', key: 'action' },
        { label: 'Outcome', html: true, render: function (row) { return badge(row.outcome); } },
        { label: 'Target', render: function (row) { return escapeHtml((row.targetType || '') + ' ' + short(row.targetId, 40)); } }
      ];
      var list = el('audit-list');
      list.innerHTML = table(columns, data.items);
      bindRows(list, data.items, function (row) {
        openDetail(row.action, '<pre>' + escapeHtml(JSON.stringify(row.metadata || {}, null, 2)) + '</pre>');
      }, []);
    } catch (error) { failure(error); }
  };

  el('audit-load').addEventListener('click', load);
  await load();
};

RENDERERS.settings = async function () {
  try {
    var data = await api('/settings');
    var rows = Object.keys(data.settings).map(function (key) {
      return { key: key, value: JSON.stringify(data.settings[key]) };
    });
    el('main').innerHTML = '<h2>Settings</h2>'
      + '<p class="muted">Read-only. These are deployment environment variables; change them on the server, where the change is auditable.</p>'
      + table([{ label: 'Setting', key: 'key' }, { label: 'Value', key: 'value' }], rows);
  } catch (error) { failure(error); }
};

el('signin-form').addEventListener('submit', async function (event) {
  event.preventDefault();
  var button = el('signin-button');
  button.disabled = true;
  try {
    var payload = await api('/auth/login', { method: 'POST', body: {
      identifier: el('identifier').value.trim(),
      password: el('password').value
    }});
    state.token = payload.accessToken;
    state.csrf = payload.csrfToken || cookieValue('gp_admin_csrf');
    el('password').value = '';
    showApp(payload.admin, null);
    var me = await api('/auth/me');
    showApp(me.admin, me.permissions);
    go('overview');
  } catch (error) {
    el('signin-error').textContent = error.message === 'invalid_credentials'
      ? 'That email, mobile number or password is not correct.'
      : error.message;
    el('signin-error').hidden = false;
  } finally {
    button.disabled = false;
  }
});

el('signout').addEventListener('click', async function () {
  try { await api('/auth/logout', { method: 'POST' }); } catch (ignored) {}
  state.token = null; state.permissions = []; state.csrf = null;
  showSignin('');
});

el('refresh-session').addEventListener('click', async function () {
  try {
    var payload = await api('/auth/refresh', { method: 'POST', body: {} });
    state.token = payload.accessToken;
    state.csrf = payload.csrfToken || state.csrf;
    window.alert('Session refreshed.');
  } catch (error) { showSignin('Your session ended. Sign in again.'); }
});

el('detail-close').addEventListener('click', function () { el('detail').close(); });
el('compose-close').addEventListener('click', function () { el('compose').close(); });

el('compose-form').addEventListener('submit', async function (event) {
  event.preventDefault();
  var kind = el('compose-target-kind').value;
  var body = { subject: el('compose-subject').value.trim(), body: el('compose-body').value.trim() };
  if (kind === 'user') body.targetUserId = el('compose-target-id').value.trim();
  else body.targetChannelId = el('compose-target-id').value.trim();
  try {
    await api('/messages', { method: 'POST', body: body });
    el('compose-form').reset();
    el('compose').close();
    go('messages');
  } catch (error) {
    el('compose-error').textContent = error.message;
    el('compose-error').hidden = false;
  }
});

(async function bootstrap() {
  state.csrf = cookieValue('gp_admin_csrf');
  if (!state.csrf) { showSignin(''); return; }
  try {
    var payload = await api('/auth/refresh', { method: 'POST', body: {} });
    state.token = payload.accessToken;
    state.csrf = payload.csrfToken || state.csrf;
    showApp(payload.admin, null);
    var me = await api('/auth/me');
    showApp(me.admin, me.permissions);
    go('overview');
  } catch (error) {
    showSignin('');
  }
})();
`;

/**
 * The dashboard's own routes.
 *
 * No authentication on the HTML itself, and that is deliberate rather than
 * careless: the page contains no data. Everything it displays comes from
 * `/admin/api`, which authenticates and authorizes every request. A login wall
 * in front of a shell would be decoration.
 *
 * `Cache-Control: no-store` because a cached dashboard is a dashboard from
 * before the moderator's last action, and `/admin/app.js` is served with the
 * same header so a browser cannot run a stale client against a newer API.
 */
export function buildDashboardRouter(): Router {
  const router = Router();

  router.get('/', (_req: Request, res: Response) => {
    res.setHeader('Cache-Control', 'no-store');
    res.type('html').send(SHELL);
  });

  router.get('/app.css', (_req: Request, res: Response) => {
    res.setHeader('Cache-Control', 'no-store');
    res.type('text/css').send(CSS);
  });

  router.get('/app.js', (_req: Request, res: Response) => {
    res.setHeader('Cache-Control', 'no-store');
    res.type('application/javascript').send(SCRIPT);
  });

  return router;
}
