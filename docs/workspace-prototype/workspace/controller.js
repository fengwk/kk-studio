import { sampleData, emptyData, documentationSampleData, a, u, mkThread, mkSession, mkFolder, mkCanvas, canvasSnapshot, copyMsgs, AGENTS, nid, trig } from '../mock/workspace-fixtures.js';
import { DAY, HOUR, MIN, WEEK, LOCAL_TZ, TZS, pad, stamp, fmtRun, evalTrigger, nextRun } from './schedule.js';
import { leaves as layoutLeaves, paneLetter, minWidth, minHeight, splitPane, closePane, swapPanes } from './layout.js';
import { createPaneLayoutView } from './pane-layout-view.js';
import { showPanePicker } from './pane-picker.js';
import { bindTreeDrag } from './tree-drag.js';
import { normalizeThreadName } from '@/features/thread/thread-name';
import { environmentActions, environments } from '../mock/thread-data';
import { svg } from './icons.js';
import { createElement } from 'react';
import { mountDomView } from './dom-view';
import { ResourceTree } from './ResourceTree';
import { ScheduleList } from './ScheduleList';
import { ThreadPaneActions } from './ThreadPane';
import { WorkspaceMenu } from './WorkspaceMenu';
import { builtinAdminId, getSession, setSessionUser, subscribeSession } from './session';

// In-memory interaction controller for the design review. The React views consume its snapshot.
export function startWorkspaceController() {
  /* ---------- 常量 ---------- */
  var SPLIT = {
    row: { label: '向右拆分', icon: 'splitR' },
    col: { label: '向下拆分', icon: 'splitD' }
  };
  var NARROW = 960;
  var STATUS = { needs: '需要你', running: '运行中', waiting: '等待', error: '失败', idle: '空闲' };
  var ORDER = Object.keys(STATUS);
  const spacesByUser = new Map([[builtinAdminId, [sampleData(), emptyData('读书笔记'), documentationSampleData()]]]);
  const activeByUser = new Map();
  let workspaceUserId;
  let chatSpaces;
  let chatSpace;

  /* ---------- 状态 ---------- */
  var M = null;            // 数据：目录、会话、Thread、定时任务
  var L = null;            // 窗格布局：二叉拆分树和聚焦窗格
  var V = { sideW: 280 };  // 界面状态
  var SCH = { edit: null, kind: 'repeat', syncing: false };
  const runsPerPage = 5;
  var timers = [];
  let pendingName;
  var ready = false;
  var el = {};
  var paneView;
  var targets;
  const blankDraft = { k: 'draft', text: '' };

  /* ---------- 工具 ---------- */
  function byId(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function toast(text) { window.htmlviewUI.toast(text); }
  function clip(text, n) {
    var chars = Array.from(String(text).replace(/\s+/g, ' ').trim());
    return chars.length > n ? chars.slice(0, n).join('') + '…' : chars.join('');
  }
  function ago(ts) {
    var d = Date.now() - ts;
    if (d < MIN) { return '刚刚'; }
    if (d < HOUR) { return Math.floor(d / MIN) + ' 分钟前'; }
    if (d < DAY) { return Math.floor(d / HOUR) + ' 小时前'; }
    if (d < 2 * DAY) { return '昨天'; }
    if (d < 7 * DAY) { return Math.floor(d / DAY) + ' 天前'; }
    var x = new Date(ts);
    return (x.getMonth() + 1) + '-' + x.getDate();
  }
  function focusKey(k) { var n = el.app.querySelector('[data-fkey="' + k + '"]'); if (n) { n.focus(); } }
  function isDialogOpen() { return !!el.screen.querySelector('dialog[open]'); }

  /* 重绘时按 data-fkey 找回焦点和光标，避免输入被打断。 */
  function withFocus(fn) {
    var ae = document.activeElement;
    var key = ae && ae.getAttribute ? ae.getAttribute('data-fkey') : null;
    var range = key && typeof ae.selectionStart === 'number' ? [ae.selectionStart, ae.selectionEnd] : null;
    fn();
    if (!key || ae.isConnected) { return; }
    var n = el.app.querySelector('[data-fkey="' + key + '"]');
    if (!n) { return; }
    n.focus({ preventScroll: true });
    if (range && typeof n.selectionStart === 'number') { n.setSelectionRange(range[0], range[1]); }
  }

  /* ---------- 查找 ---------- */
  function allFolders(list) {
    return (list || M.folders).flatMap(function (f) { return [f].concat(allFolders(f.folders)); });
  }
  function parentFolder(f, folders) { return allFolders(folders).find(function (p) { return p.folders.includes(f); }) || null; }
  function siblingsOf(f) { var p = parentFolder(f); return p ? p.folders : M.folders; }
  function folderPath(f, folders) {
    var path = [];
    for (; f; f = parentFolder(f, folders)) { path.unshift(f.name); }
    return path.join(' / ');
  }
  function expandTo(f) { for (; f; f = parentFolder(f)) { f.open = true; } }
  function sessionsIn(f) { return f.resources.filter(function (r) { return r.kind === 'session'; }); }
  function eachThread(fn, folders) { allFolders(folders).forEach(function (f) { sessionsIn(f).forEach(function (s) { s.threads.forEach(function (t) { fn(t, s, f); }); }); }); }
  function findThread(id, folders) { var r = null; eachThread(function (t, s, f) { if (t.id === id) { r = { t: t, s: s, f: f }; } }, folders); return r; }
  function findResource(id) {
    for (var f of allFolders()) {
      var resource = f.resources.find(function (item) { return item.id === id; });
      if (resource) { return { resource: resource, f: f }; }
    }
    return null;
  }
  function findSession(id) {
    var x = findResource(id);
    return x && x.resource.kind === 'session' ? { s: x.resource, f: x.f } : null;
  }
  function findFolder(id) { return allFolders().find(function (f) { return f.id === id; }) || null; }
  function findSched(id) { return M.schedules.find(function (c) { return c.id === id; }) || null; }
  function threadTitle(x) { return x.s.threads.length > 1 ? x.s.title + ' › ' + x.t.name : x.s.title; }
  function best(list) { var r = 'idle'; list.forEach(function (st) { if (ORDER.indexOf(st) < ORDER.indexOf(r)) { r = st; } }); return r; }
  function sessionStatus(s) { return best(s.threads.map(function (t) { return t.status; })); }
  function folderStatus(f) { return best(sessionsIn(f).map(sessionStatus).concat(f.folders.map(folderStatus))); }
  function refsFolder(f) { return M.schedules.filter(function (c) { return c.action.type === 'new' && c.action.folderId === f.id; }); }
  function refsThread(t) { return M.schedules.filter(function (c) { return c.action.threadId === t.id; }); }
  function refsSession(s) {
    return M.schedules.filter(function (c) {
      return (c.action.type === 'branch' && c.action.sessionId === s.id) || (c.action.type === 'continue' && s.threads.some(function (t) { return t.id === c.action.threadId; }));
    });
  }

  /* ---------- 窗格和资源绑定 ---------- */
  function leaves() { return layoutLeaves(L.root); }
  function leafById(id) { return leaves().find((leaf) => leaf.id === id) || null; }
  function targetOf(id) { return targets.get(id) ?? blankDraft; }
  function leafOfTarget(target) {
    const same = leaves().filter(({ id }) => {
      const current = targetOf(id);
      return current.k === target.k && current.id === target.id && current.view === target.view;
    });
    return same.find(({ id }) => id === L.focus) ?? same[0];
  }
  function presentPane(id) {
    const target = targetOf(id);
    if (target.k === 'thread') {
      const { t, s } = findThread(target.id);
      return { titleParts: [s.title, t.name], actions: createElement(ThreadPaneActions, {
        status: t.status, stop: () => stopThread(t.id),
      }) };
    }
    if (target.k === 'canvas') return { titleParts: [findResource(target.id).resource.title] };
    if (target.k === 'environment') return { titleParts: [environments.find((env) => env.id === target.id).name,
      environmentActions.find(({ view }) => view === target.view).label] };
    return { titleParts: ['新会话'] };
  }

  /* ---------- 选择与当前目录 ---------- */
  function keyFolderId(key) {
    if (!key) { return null; }
    var k = key.charAt(0);
    var id = key.slice(2);
    if (k === 'f') { return findFolder(id) ? id : null; }
    if (k === 's' || k === 'b') { var resource = findResource(id); return resource ? resource.f.id : null; }
    if (k === 't') { var x = findThread(id); return x ? x.f.id : null; }
    return null;
  }
  function curFolderId() { return keyFolderId(V.sel) || M.folders[0].id; }
  function threadKey(tid) { return 't:' + tid; }
  function select(key) { V.sel = key; V.tabKey = key; }

  /* ---------- 渲染入口 ---------- */
  function render() {
    if (V.composing) { V.pending = true; return; }
    if (V.menuAnchor?.matches('[data-action="view-select"], [data-action="pane-split"]')) { closeMenu(true); }
    computeModes();
    renderSide();
    renderPanes();
  }
  function renderSide() {
    el.chatSpaceName.textContent = chatSpace.title;
    renderTree();
    renderThreadsView();
    renderSched();
  }
  function computeModes() {
    var narrow = el.screen.clientWidth <= NARROW;
    if (!narrow && V.drawer) { closeDrawer(); }
    el.app.classList.toggle('x-narrow', narrow);
    el.app.classList.toggle('x-side-off', !!V.sideOff);
    el.app.classList.toggle('x-pane-fullscreen', !!V.fullscreen);
    el.side.inert = V.fullscreen || (narrow && !V.drawer);
    el.rail.hidden = !(V.sideOff && !narrow && !V.fullscreen);
    var tabsMode = leaves().length > 1 && (narrow || minWidth(L.root) > el.panes.clientWidth || minHeight(L.root) > el.panes.clientHeight);
    var changed = narrow !== V.narrow || tabsMode !== V.tabsMode;
    V.narrow = narrow;
    V.tabsMode = tabsMode;
    el.app.classList.toggle('x-tabsmode', tabsMode);
    return changed;
  }

  /* ---------- 目录视图 ---------- */
  function rowKey(r) {
    if (r.kind === 'folder') { return 'f:' + r.f.id; }
    if (r.kind === 'session') { return 's:' + r.s.id; }
    if (r.kind === 'canvas') { return 'b:' + r.canvas.id; }
    if (r.kind === 'thread') { return 't:' + r.t.id; }
    return '';
  }
  function treeModel() {
    var q = V.qTree.trim().toLowerCase();
    function folderRows(f, level, ancestorHit) {
      var hitFolder = ancestorHit || (q && f.name.toLowerCase().includes(q));
      var inner = [];
      f.resources.forEach(function (s) {
        if (s.kind === 'canvas') {
          if (!q || hitFolder || s.title.toLowerCase().includes(q)) { inner.push({ kind: 'canvas', canvas: s, f: f, level: level + 1 }); }
          return;
        }
        var hitSession = q && s.title.toLowerCase().includes(q);
        var hitThreads = q ? s.threads.filter(function (t) { return t.name.toLowerCase().includes(q); }) : [];
        if (q && !hitFolder && !hitSession && !hitThreads.length) { return; }
        var open = s.open || hitThreads.length > 0;
        inner.push({ kind: 'session', s: s, f: f, level: level + 1, expandable: true, open: open });
        if (open) {
          (q && !hitFolder && !hitSession ? hitThreads : s.threads).forEach(function (t) { inner.push({ kind: 'thread', t: t, s: s, f: f, level: level + 2 }); });
        }
      });
      var children = f.folders.flatMap(function (child) { return folderRows(child, level + 1, hitFolder); });
      if (q && !hitFolder && !inner.length && !children.length) { return []; }
      var expandable = !!(inner.length || children.length);
      var open = expandable && (f.open || !!q);
      var rows = [{ kind: 'folder', f: f, level: level, expandable: expandable, open: open }];
      if (!open) { return rows; }
      return rows.concat(inner, children);
    }
    return M.folders.flatMap(function (f) { return folderRows(f, 1, false); });
  }
  function shownSel(keys) {
    var k = V.sel;
    if (!k) { return null; }
    if (keys.indexOf(k) >= 0) { return k; }
    var fid = keyFolderId(k);
    if (k.charAt(0) === 't') {
      var x = findThread(k.slice(2));
      if (x && keys.indexOf('s:' + x.s.id) >= 0) { return 's:' + x.s.id; }
    }
    for (var f = findFolder(fid); f; f = parentFolder(f)) {
      if (keys.includes('f:' + f.id)) { return 'f:' + f.id; }
    }
    return null;
  }
  function stIcon(st) {
    if (!st || st === 'idle') { return '<span class="x-st" aria-hidden="true"></span>'; }
    return '<span class="x-st x-st-' + st + '" role="img" aria-label="' + STATUS[st] + '" title="' + STATUS[st] + '">' + svg(st) + '</span>';
  }
  function label(key, text, statusView) {
    if (V.renaming === key && !!V.renameInStatus === !!statusView) { return '<input class="x-rename" data-fkey="rename" maxlength="80" aria-label="重命名「' + esc(text) + '」" value="' + esc(V.renameVal) + '">'; }
    return '<span class="x-row-label" data-allow-truncate>' + esc(text) + '</span>';
  }
  function treeRow(row, selected) {
    const key = rowKey(row);
    const { kind, level, open } = row;
    const movable = !V.qTree.trim() && V.renaming !== key && ['folder', 'session', 'canvas'].includes(kind);
    const props = {
      key, kind, level, selected: key === selected, tabIndex: key === V.tabKey ? 0 : -1,
      movable, draggable: movable && matchMedia('(any-pointer: fine)').matches,
      rename: V.renaming === key && !V.renameInStatus ? V.renameVal : undefined,
    };
    switch (kind) {
      case 'folder': {
        const count = open ? 0 : [row.f, ...allFolders(row.f.folders)].reduce((sum, folder) => sum + folder.resources.length, 0);
        const status = !open && folderStatus(row.f);
        return { ...props, title: row.f.name, expanded: row.expandable ? open : undefined, count: count || undefined,
          status: status && status !== 'idle' ? status : undefined };
      }
      case 'session': return { ...props, title: row.s.title, expanded: open, status: open ? undefined : sessionStatus(row.s), count: row.s.threads.length };
      case 'canvas': return { ...props, title: row.canvas.title };
      case 'thread': return { ...props, title: row.t.name, status: row.t.status, time: ago(row.t.at) };
    }
  }
  function renderTree() {
    var rows = treeModel();
    V.rows = rows;
    var keys = V.rows.map(rowKey);
    var shown = shownSel(keys);
    if (keys.indexOf(V.tabKey) < 0) { V.tabKey = shown || keys[0] || null; }
    withFocus(() => mountDomView(el.tree, createElement(ResourceTree, { rows: rows.map((row) => treeRow(row, shown)) })));
  }

  /* ---------- Thread 视图：按状态分组，组内按最近活动排序 ---------- */
  function renderThreadsView() {
    var q = V.qThreads.trim().toLowerCase();
    var groups = Object.fromEntries(ORDER.map(function (st) { return [st, []]; }));
    let total = 0;
    let hasPending = false;
    for (const space of chatSpaces) {
      const local = space === chatSpace;
      const folders = space.model.folders;
      eachThread((t, s, f) => {
        if (!local && t.status !== 'needs') return;
        total += 1;
        if (t.status === 'needs') hasPending = true;
        const path = folderPath(f, folders);
        const location = local ? path : `${space.title} · ${path}`;
        if (q && !`${s.title} ${t.name} ${location}`.toLowerCase().includes(q)) return;
        groups[t.status].push({ t, s, location, local });
      }, folders);
    }
    for (const control of el.app.querySelectorAll('[data-tab="x-view-threads"], [data-action="side-expand"], [data-action="drawer-open"]')) {
      control.toggleAttribute('data-pending-interaction', hasPending);
      if (hasPending) control.setAttribute('aria-describedby', 'x-pending-description');
      else control.removeAttribute('aria-describedby');
    }
    if (!total) {
      el.threads.innerHTML = '<div class="empty x-empty"><h3>还没有 Thread</h3></div>';
      return;
    }
    const target = targetOf(L.focus);
    var focusTid = target.k === 'thread' ? target.id : null;
    var html = ORDER.map(function (st) {
      var list = groups[st].sort(function (a, b) { return b.t.at - a.t.at; });
      if (!list.length) { return ''; }
      var open = q ? true : V.groups[st];
      return '<section class="x-group" aria-label="' + STATUS[st] + '"><button type="button" class="x-group-head" data-action="group-toggle" data-st="' + st + '" aria-expanded="' + open + '" data-fkey="grp:' + st + '">' + svg('chev', 'x-chev') + stIcon(st) + '<span>' + STATUS[st] + '</span><span class="x-count">' + list.length + '</span></button>' +
        (open ? '<div role="list">' + list.map(function (x) { return trowHTML(x, focusTid); }).join('') + '</div>' : '') + '</section>';
    }).join('');
    withFocus(function () { el.threads.innerHTML = html || '<p class="x-none">没有匹配项</p>'; });
  }
  function trowHTML(x, focusTid) {
    var key = threadKey(x.t.id);
    var main = x.local && V.renaming === key && V.renameInStatus ? label(key, x.t.name, true) :
      '<button type="button" class="x-trow-main" data-action="tv-open" data-fkey="tv:' + x.t.id + '">' + stIcon(x.t.status) +
      '<span class="x-trow-text"><span class="x-trow-title" data-allow-truncate>' + esc(x.s.title + ' › ' + x.t.name) + '</span><span class="x-trow-sub" data-allow-truncate>' + esc(x.location + ' · ' + ago(x.t.at)) + '</span></span></button>';
    const menu = x.local ? '<button type="button" class="icon-button is-compact x-ib x-more" data-action="tv-menu" aria-label="更多操作">' + svg('more') + '</button>' : '';
    return '<div class="x-trow' + (x.t.id === focusTid ? ' x-sel' : '') + '" role="listitem" data-tid="' + x.t.id + '">' + main + menu + '</div>';
  }
  function openStatusThread(id, anchor) {
    const target = { k: 'thread', id };
    if (findThread(id)) { requestOpen(target, anchor); return; }
    const space = chatSpaces.find((candidate) => candidate !== chatSpace
      && findThread(id, candidate.model.folders)?.t.status === 'needs');
    if (!space) return;
    const drawer = V.drawer;
    switchChatSpace(space);
    const statusTab = el.side.querySelector('[data-tab="x-view-threads"]');
    statusTab.click();
    if (drawer) openDrawer();
    requestOpen(target, el.threads.querySelector(`[data-fkey="tv:${id}"]`) ?? statusTab);
  }

  /* ---------- 定时视图 ---------- */
  function scheduleTime(at, timeZone, includeYear = false) {
    const full = fmtRun(at, timeZone, true);
    return { dateTime: new Date(at).toISOString(), label: includeYear ? full : fmtRun(at, timeZone), title: `${full} · ${timeZone}` };
  }
  function renderSched() {
    const rows = M.schedules.map((c) => {
      const next = nextRun(c);
      const row = { id: c.id, name: c.name, enabled: c.enabled, next: next ? scheduleTime(next, c.trigger.tz) : undefined };
      if (c.id !== V.scheduleHistory) return row;

      const start = V.schedulePage * runsPerPage;
      const records = c.runs.slice(start, start + runsPerPage).map((run) => {
        const output = run.threadId ? findThread(run.threadId) : null;
        const record = { id: run.id, time: scheduleTime(run.at, run.timeZone, true) };
        return output ? { ...record, thread: threadTitle(output) } : { ...record, reason: run.reason ?? 'Thread 已删除' };
      });
      return { ...row, history: { records, hasNewer: start > 0, hasOlder: start + runsPerPage < c.runs.length } };
    });
    mountDomView(el.sched, createElement(ScheduleList, { rows, onToggle: setScheduleEnabled }));
  }
  function setScheduleEnabled(id, enabled) {
    const c = findSched(id);
    c.enabled = enabled;
    renderSched();
    toast((enabled ? '已启用「' : '已暂停「') + c.name + '」');
  }
  function toggleScheduleHistory(c) {
    V.scheduleHistory = V.scheduleHistory === c.id ? null : c.id;
    V.schedulePage = 0;
    renderSched();
  }
  function turnSchedulePage(c, step) {
    const next = V.schedulePage + step;
    if (next < 0 || next * runsPerPage >= c.runs.length) return;
    V.schedulePage = next;
    renderSched();
    const action = next === 0 ? 'older' : (next + 1) * runsPerPage >= c.runs.length ? 'newer' : null;
    if (action) focusKey(`sch-${action}:${c.id}`);
  }
  function openScheduleRun(c, anchor) {
    const run = c.runs.find(({ id }) => id === anchor.dataset.run);
    if (run?.threadId && findThread(run.threadId)) requestOpen({ k: 'thread', id: run.threadId }, anchor);
  }

  /* ---------- 窗格 ---------- */
  function renderPanes() {
    withFocus(() => paneView.render(L, {
      narrow: V.narrow, single: V.tabsMode || !!V.fullscreen, fullscreen: !!V.fullscreen, present: presentPane,
    }));
    syncProjectView();
  }
  function onPaneAction(id, action, anchor, dir) {
    if (anchor === V.menuClosedFrom) { V.menuClosedFrom = null; return; }
    if (anchor.getAttribute('aria-disabled') === 'true') { toast(anchor.title); return; }
    switch (action) {
      case 'pane-close': closeLeaf(id); break;
      case 'pane-fullscreen': toggleFullscreen(id); break;
      case 'pane-split':
        if (dir) splitLeaf(id, dir);
        else openMenu(anchor, Object.entries(SPLIT).map(([direction, item]) => ({
          ...item, restoresFocus: false, run: () => splitLeaf(id, direction),
        })));
        break;
      case 'view-select': viewMenu(anchor); break;
    }
  }
  function viewMenu(anchor) {
    const order = leaves();
    const items = order.map((leaf) => {
      const title = presentPane(leaf.id).titleParts.join(' › '), letter = paneLetter(order, leaf);
      return { label: title, letter, accessibleLabel: `${letter}：${title}`, current: leaf.id === L.focus,
        restoresFocus: false, run: () => { setFocusLeaf(leaf.id); paneView.focusHeader(); } };
    });
    items.push({ sep: true }, { label: '关闭当前窗格', icon: 'x', run: () => closeLeaf(L.focus) });
    openMenu(anchor, items);
  }
  function focusComposer(id) {
    requestAnimationFrame(function () {
      var t = el.panes.querySelector('[data-leaf="' + id + '"] [contenteditable="true"]');
      if (t) { t.focus(); }
    });
  }
  function syncProjectView() {
    var state = {
      narrow: V.narrow,
      panes: paneView.mounts().map((mount) => {
        const { id } = mount;
        const target = targetOf(id);
        const base = { ...mount, kind: target.k };
        if (target.k === 'environment') return { ...base, targetKey: `${target.view}:${target.id}`, view: target.view };
        const targetKey = `${target.k}:${target.id ?? id}`;
        if (target.k === 'canvas') return { ...base, targetKey, canvas: canvasSnapshot(findResource(target.id).resource) };
        const x = target.k === 'thread' ? findThread(target.id) : null;
        const t = x?.t;
        function setDraft(text) {
          if (t) { t.draft = text; return; }
          const draft = { ...targetOf(id), text };
          if (text.trim() && !draft.folderId) draft.folderId = M.folders[0].id;
          targets.set(id, draft);
        }
        return {
          ...base, targetKey, draft: t ? t.draft : target.text,
          agent: x?.s.agent,
          messages: t?.msgs ?? [], queuedMessages: t?.queue ?? [], status: t?.status ?? 'idle',
          approval: t?.status === 'needs' && t.pending ? {
            threadId: t.id, invocationId: `${t.id}-approval`, command: t.pending,
            decide: (decision, reason) => decideApproval(t.id, decision, reason),
          } : undefined,
          setDraft,
          send: (text) => { setDraft(text); return sendFrom(id); },
          branch: (endIndex) => askBranchName(id, endIndex, false),
        };
      }),
    };
    window.kkProjectView = state;
    window.dispatchEvent(new Event('kk-project-view'));
  }

  /* ---------- 打开与窗格操作 ---------- */
  function setFocusLeaf(id) {
    if (L.focus === id) { return; }
    L.focus = id;
    renderThreadsView();
    if (V.tabsMode || V.fullscreen) { renderPanes(); }
    else { paneView.applyFocus(); syncProjectView(); }
  }
  function requestOpen(target, anchor) {
    if (anchor && anchor === V.menuClosedFrom) { V.menuClosedFrom = null; return; }
    closeMenu(false);
    const at = target.k === 'draft' ? null : leafOfTarget(target);
    if (anchor && !V.fullscreen) {
      const openAt = (id) => {
        if (at?.id === id) {
          setFocusLeaf(id);
          if (V.drawer) closeDrawer();
          paneView.focusHeader();
          return;
        }
        openInPane(id, target, { focusHeader: true, beforeOpen() {
          if (at && target.k !== 'canvas') targets.delete(at.id);
        } });
      };
      const current = targetOf(L.focus);
      if (current.k === 'draft' && !current.text.trim()) {
        openAt(L.focus);
      } else {
        const choices = paneView.mounts().map((mount, index) => ({ ...mount,
          letter: String.fromCharCode(65 + index), title: presentPane(mount.id).titleParts.join(' › '),
        }));
        showPanePicker(el.main, choices, L.focus, anchor, openAt);
      }
      return;
    }
    if (at) {
      setFocusLeaf(at.id);
      if (V.drawer) closeDrawer();
      paneView.flash(at.id);
      return;
    }
    openInPane(L.focus, target);
  }
  function openEnvironment(view, id, anchor) {
    if (environmentActions.some((action) => action.view === view) && environments.some((env) => env.id === id && env.ready)) {
      requestOpen({ k: 'environment', view, id }, anchor);
    }
  }
  function openInPane(id, target, { beforeOpen, focusHeader = false } = {}) {
    const current = targetOf(id);
    const go = () => {
      beforeOpen?.();
      targets.set(id, target.k === 'draft' ? { ...target, id: nid('d'), text: '' } : target);
      L.focus = id;
      if (V.narrow) closeDrawer();
      render();
      paneView.flash(id);
      if (target.k === 'draft') focusComposer(id);
      else if (focusHeader) paneView.focusHeader();
    };
    if (current.k === 'draft' && current.text.trim()) {
      confirmDlg({ title: '丢弃未发送的新会话？', body: '<p>在这个窗格打开其他内容会丢弃已输入的内容。</p>', ok: '丢弃并打开', onOk: go });
    } else go();
  }
  function splitLeaf(id, dir) {
    const why = paneView.canSplit(id, dir);
    if (why) { toast(why); return; }
    splitPane(L, leafById(id), dir, nid('x'), nid('p'));
    render();
    paneView.focusHeader();
  }
  function closeLeaf(id) {
    if (leaves().length === 1) return;
    const target = targetOf(id);
    const go = () => {
      targets.delete(id);
      closePane(L, leafById(id));
      render();
      paneView.focusHeader();
    };
    if (target.k === 'draft' && target.text.trim()) {
      confirmDlg({ title: '丢弃未发送的新会话？', body: '<p>关闭窗格会丢弃已输入的内容。</p>', ok: '丢弃并关闭', onOk: go });
    } else go();
  }
  function swapLeaves(fromId, toId) {
    swapPanes(L, leafById(fromId), leafById(toId));
    render();
  }
  function toggleFullscreen(id) {
    L.focus = id;
    V.fullscreen = !V.fullscreen;
    render();
    paneView.focusFullscreen();
  }
  function unbindTargets(matches) {
    for (const [id, target] of targets) {
      if (matches(target)) targets.delete(id);
    }
  }
  function openDrawer() {
    V.drawer = true;
    el.app.classList.add('x-drawer');
    el.scrim.hidden = false;
    el.side.inert = false;
    el.main.inert = true;
    var row = el.tree.closest('[hidden]') ? null : el.tree.querySelector('[tabindex="0"]');
    (row || el.side.querySelector('[data-tab][aria-selected="true"]')).focus();
  }
  function closeDrawer() {
    var restore = V.drawer && V.narrow;
    V.drawer = false;
    el.app.classList.remove('x-drawer');
    el.scrim.hidden = true;
    el.side.inert = V.narrow;
    el.main.inert = false;
    if (restore) { el.app.querySelector('[data-action="drawer-open"]').focus(); }
  }

  /* ---------- 会话与 Thread 的动作（模拟运行） ---------- */
  function reply(thread, ms, text) {
    var h = setTimeout(function () {
      timers = timers.filter(function (tm) { return tm.h !== h; });
      if (thread.status !== 'running') { return; }
      thread.msgs.push(a(text));
      settleMockTurn(thread);
      if (findThread(thread.id)) render();
    }, ms);
    timers.push({ h: h, tid: thread.id });
  }
  function stopTimers(tids) {
    timers = timers.filter(function (tm) {
      if (tids.indexOf(tm.tid) < 0) { return true; }
      clearTimeout(tm.h);
      return false;
    });
  }
  function sendFrom(id) {
    const target = targetOf(id);
    if (target.k === 'draft') {
      var text = target.text.trim();
      if (!text) { return null; }
      var f = findFolder(target.folderId);
      const t = mkThread('main', 'running', Date.now(), [u(text)]);
      var s = mkSession(clip(text, 40), [t], true, AGENTS[0]);
      f.resources.unshift(s);
      expandTo(f);
      targets.set(id, { k: 'thread', id: t.id });
      reply(t, 2600, '（模拟回复）收到，开始处理。');
      render();
      return 'thread:' + t.id;
    }
    var x = findThread(target.id);
    if (!x) { return null; }
    var msg = x.t.draft.trim();
    if (!msg) { return null; }
    const t = x.t;
    t.draft = '';
    if (busy(x)) {
      const idempotencyKey = nid('q');
      t.queue = [...(t.queue ?? []), { idempotencyKey, role: 'user', text: msg, sequence: String(t.queue?.length ?? 0) }];
    } else {
      startMockTurn(t, msg);
    }
    render();
    return 'thread:' + x.t.id;
  }
  function startMockTurn(thread, text) {
    thread.msgs.push(u(text));
    thread.status = 'running';
    thread.at = Date.now();
    reply(thread, 2600, '（模拟回复）收到，已处理。');
  }
  function settleMockTurn(thread) {
    thread.status = 'idle';
    thread.at = Date.now();
    if (!thread.queue?.length) return;
    const [next, ...rest] = thread.queue;
    thread.queue = rest;
    startMockTurn(thread, next.text);
  }
  function decideApproval(id, decision, reason) {
    var x = findThread(id);
    if (!x || x.t.status !== 'needs' || !x.t.pending || (decision !== 'ALLOW' && decision !== 'DENY')) { return; }
    var t = x.t;
    t.pending = null;
    var result = decision === 'ALLOW' ? '已允许。' : '已拒绝。' + (reason ? '原因：' + reason : '');
    t.msgs.push(a('（离线示例）' + result + '未执行命令。', 'no-usage'));
    settleMockTurn(t);
    render();
  }
  function stopThread(id) {
    var x = findThread(id);
    if (!x || x.t.status !== 'running') { return; }
    var t = x.t;
    t.at = Date.now();
    stopTimers([t.id]);
    t.status = 'idle';
    t.queue = [];
    t.msgs.push(a('已停止。', 'no-usage'));
    render();
  }
  function uniqueThreadName(s, base) {
    var n = s.threads.length;
    var name;
    do { name = base + ' ' + n; n += 1; } while (s.threads.some(function (t) { return t.name === name; }));
    return name;
  }
  function askBranchName(id, endIndex, newSession) {
    const target = targetOf(id);
    const x = target.k === 'thread' ? findThread(target.id) : null;
    if (x?.t.msgs[endIndex]?.turnEnd == null) { return; }
    askName({
      title: newSession ? '从此处新建会话' : '从此处分支',
      label: newSession ? '会话名称' : '分支名称',
      value: newSession ? clip(x.s.title, 36) + ' · 分叉' : uniqueThreadName(x.s, '分支'),
      normalize: newSession ? undefined : normalizeThreadName,
      validate: newSession ? undefined : (name) => x.s.threads.some((t) => t.name === name) ? '这个会话里已有同名分支。' : '',
      onConfirm: (name) => branchFrom(id, x, endIndex, newSession, name),
    });
  }
  function branchFrom(id, x, endIndex, newSession, name) {
    const t = mkThread(newSession ? 'main' : name, 'idle', Date.now(), copyMsgs(x.t.msgs.slice(0, endIndex + 1)));
    if (newSession) {
      x.f.resources.unshift(mkSession(name, [t], true, x.s.agent));
    } else {
      x.s.threads.push(t);
      x.s.open = true;
    }
    expandTo(x.f);
    targets.set(id, { k: 'thread', id: t.id });
    L.focus = id;
    render();
    paneView.flash(id);
    focusComposer(id);
    toast(newSession ? '已从此处新建会话，原会话保持不变' : '已新建 Thread「' + name + '」，排在「' + x.s.title + '」末尾');
  }

  /* ---------- 目录、会话、Thread 的管理 ---------- */
  let canvasAnchor = null;
  function newCanvas(folderId, anchor) {
    canvasAnchor = anchor;
    var input = byId('canvas-name');
    input.form.dataset.folderId = folderId;
    input.value = '';
    input.setCustomValidity('');
    window.htmlviewUI.open('dlg-canvas');
  }
  function createCanvas(e) {
    e.preventDefault();
    var input = byId('canvas-name');
    var title = input.value.trim();
    if (!title) { input.setCustomValidity('请输入画布名称。'); input.reportValidity(); return; }
    var f = findFolder(e.currentTarget.dataset.folderId);
    var canvas = mkCanvas(crypto.randomUUID(), title, false);
    // Creating and opening are separate: the canvas exists even if choosing a pane is cancelled.
    f.resources.unshift(canvas);
    expandTo(f);
    V.qTree = '';
    byId('x-filter-tree').value = '';
    window.htmlviewUI.close();
    render();
    requestOpen({ k: 'canvas', id: canvas.id }, canvasAnchor);
  }
  function askDeleteCanvas(x) {
    var canvas = x.resource;
    confirmDlg({ title: '删除画布「' + canvas.title + '」？', body: '<p>画布内容会被删除，无法恢复。</p>', ok: '删除画布', onOk: function () {
      x.f.resources.splice(x.f.resources.indexOf(canvas), 1);
      unbindTargets((target) => target.k === 'canvas' && target.id === canvas.id);
      select('f:' + x.f.id);
      render();
      toast('已删除画布「' + canvas.title + '」');
    } });
  }
  function newFolder(parent = null) {
    var list = parent ? parent.folders : M.folders;
    var name = '新目录';
    var n = 2;
    while (folderNameUsed(list, name)) { name = '新目录 ' + n; n += 1; }
    var f = mkFolder(name, [], true);
    list.push(f);
    if (parent) { expandTo(parent); }
    V.qTree = '';
    byId('x-filter-tree').value = '';
    select('f:' + f.id);
    startRename('f:' + f.id);
  }
  function folderNameUsed(list, name, except) {
    return list.some(function (f) { return f !== except && f.name.toLowerCase() === name.toLowerCase(); });
  }
  function startRename(key, statusView) {
    var k = key.charAt(0);
    var id = key.slice(2);
    if (k === 'd') { return; }
    V.renaming = key;
    V.renameInStatus = statusView;
    V.renameVal = k === 'f' ? findFolder(id).name : k === 't' ? findThread(id).t.name : findResource(id).resource.title;
    renderSide();
    var input = (statusView ? el.threads : el.tree).querySelector('.x-rename');
    if (input) { input.focus(); input.select(); }
  }
  function commitRename() {
    var key = V.renaming;
    if (!key) { return true; }
    var name = V.renameVal.trim();
    if (!name) { cancelRename(); return true; }
    var k = key.charAt(0);
    var id = key.slice(2);
    if (k === 'f') {
      var folder = findFolder(id);
      if (folderNameUsed(siblingsOf(folder), name, folder)) { toast('同一级已经有叫「' + name + '」的目录'); return false; }
      folder.name = name;
    } else if (k === 's' || k === 'b') {
      findResource(id).resource.title = name;
    } else {
      var x = findThread(id);
      if (x.s.threads.some(function (t) { return t !== x.t && t.name === name; })) { toast('这个会话里已经有叫「' + name + '」的 Thread'); return false; }
      x.t.name = name;
    }
    V.renaming = null;
    render();
    focusRenameRow(key);
    return true;
  }
  function focusRenameRow(key) { focusKey(V.renameInStatus ? 'tv:' + key.slice(2) : 'row:' + key); }
  function cancelRename() { var key = V.renaming; V.renaming = null; renderSide(); if (key) { focusRenameRow(key); } }
  function folderDeleteBlock(f) {
    if (f.folders.length) { return '目录不为空，先移走或删除子目录'; }
    if (!parentFolder(f) && M.folders.length === 1) { return '至少保留一个目录'; }
    if (f.resources.length) { return '目录不为空，先移走或删除其中的资源'; }
    if ([...targets.values()].some((target) => target.k === 'draft' && target.folderId === f.id && target.text.trim())) {
      return '这个目录有未发送的草稿，请先发送或清空草稿';
    }
    var refs = refsFolder(f);
    if (refs.length) { return '定时任务「' + refs[0].name + '」会在这里新建会话'; }
    return '';
  }
  function blockedDialog(title, threads, refs) {
    var items = threads.map(function (y) {
      return '<li>' + stIcon(y.t.status) + '<span>「' + esc(threadTitle(y)) + '」' + (y.t.status === 'running' ? '正在运行，先停止它' : y.t.status === 'waiting' ? '正在等待结果' : '在等你处理，请先处理') + '</span></li>';
    }).concat(refs.map(function (c) {
      return '<li><span class="x-ref">' + svg('clock') + '</span><span>定时任务「' + esc(c.name) + '」引用了它，请先修改这个任务的目标</span></li>';
    }));
    confirmDlg({ title: title, body: '<ul class="x-block-list">' + items.join('') + '</ul>' });
  }
  function busy(x) { return ['running', 'waiting', 'needs'].includes(x.t.status); }
  function askDeleteSession(s) {
    var x = findSession(s.id);
    var blocked = s.threads.map(function (t) { return { t: t, s: s, f: x.f }; }).filter(busy);
    var refs = refsSession(s);
    if (blocked.length || refs.length) { blockedDialog('暂时不能删除「' + s.title + '」', blocked, refs); return; }
    confirmDlg({
      title: '删除会话「' + s.title + '」？',
      body: '<p>会删除其中 ' + s.threads.length + ' 条 Thread、全部历史和附件，无法恢复。</p>',
      ok: '删除会话',
      onOk: function () { removeThreads(s, s.threads.slice()); toast('已删除会话「' + s.title + '」'); }
    });
  }
  function askDeleteThread(s, t) {
    if (s.threads.length === 1) { askDeleteSession(s); return; }
    var x = findThread(t.id);
    var refs = refsThread(t);
    if (busy(x) || refs.length) { blockedDialog('暂时不能删除「' + threadTitle(x) + '」', busy(x) ? [x] : [], refs); return; }
    confirmDlg({
      title: '删除 Thread「' + t.name + '」？',
      body: '<p>会一并删除它的子代理、只属于它的历史和不再使用的附件。同一会话的其他 Thread 不受影响，无法恢复。</p>',
      ok: '删除 Thread',
      onOk: function () { removeThreads(s, [t]); toast('已删除 Thread「' + t.name + '」'); }
    });
  }
  function askDeleteFolder(f) {
    var why = folderDeleteBlock(f);
    if (why) { toast(why); return; }
    confirmDlg({
      title: '删除目录「' + f.name + '」？',
      body: '<p>目录是空的，删除后无法恢复。</p>',
      ok: '删除目录',
      onOk: function () {
        var p = parentFolder(f);
        var list = p ? p.folders : M.folders;
        list.splice(list.indexOf(f), 1);
        for (const target of targets.values()) {
          if (target.k === 'draft' && target.folderId === f.id) delete target.folderId;
        }
        select('f:' + (p || M.folders[0]).id);
        render();
        toast('已删除目录「' + f.name + '」');
      }
    });
  }
  function removeThreads(s, list) {
    var x = findSession(s.id);
    var tids = list.map(function (t) { return t.id; });
    s.threads = s.threads.filter(function (t) { return tids.indexOf(t.id) < 0; });
    if (!s.threads.length) { x.f.resources.splice(x.f.resources.indexOf(s), 1); }
    unbindTargets((target) => target.k === 'thread' && tids.includes(target.id));
    stopTimers(tids);
    for (const c of M.schedules) {
      for (const run of c.runs) {
        if (tids.includes(run.threadId)) run.threadId = null;
      }
    }
    select(s.threads.length ? 's:' + s.id : 'f:' + x.f.id);
    render();
  }
  function canMoveFolder(f, to) {
    if (to === parentFolder(f) || to === f || allFolders(f.folders).includes(to)) { return false; }
    return !folderNameUsed(to ? to.folders : M.folders, f.name);
  }
  function reveal(tid) {
    var x = findThread(tid);
    expandTo(x.f);
    x.s.open = true;
    V.qTree = '';
    byId('x-filter-tree').value = '';
    select(threadKey(tid));
    el.app.querySelector('[data-tab="x-view-tree"]').click();
    renderSide();
    var row = el.tree.querySelector('[data-key="' + threadKey(tid) + '"]');
    if (row) { row.scrollIntoView({ block: 'nearest' }); row.focus(); }
  }

  /* ---------- 定时任务 ---------- */
  function runSchedule(c) {
    var now = Date.now();
    var act = c.action;
    const previousId = act.type === 'continue' ? act.threadId : c.runs.find((run) => !run.reason)?.threadId;
    const prev = previousId ? findThread(previousId) : null;
    const record = { id: nid('run'), at: now, timeZone: c.trigger.tz, threadId: null };
    V.scheduleHistory = c.id;
    V.schedulePage = 0;
    if (prev && busy(prev)) {
      const reason = act.type === 'continue' ? '跳过：目标 Thread 尚未结束' : '跳过：上次产出尚未结束';
      c.runs.unshift({ ...record, reason });
      renderSched();
      toast(reason);
      return;
    }
    var label = c.name + ' · ' + stamp(now);
    var t;
    if (act.type === 'new') {
      var f = findFolder(act.folderId);
      t = mkThread('main', 'running', now, [u(c.prompt)]);
      f.resources.unshift(mkSession(label, [t], false, act.agent));
      expandTo(f);
    } else if (act.type === 'branch') {
      var x = findSession(act.sessionId);
      var src = findThread(act.threadId);
      t = mkThread(uniqueThreadName(x.s, c.name), 'running', now, copyMsgs(src.t.msgs.slice(0, act.endIndex + 1)).concat([u(c.prompt)]));
      x.s.threads.push(t);
    } else {
      t = prev.t;
      t.msgs.push(u(c.prompt));
      t.status = 'running';
      t.at = now;
    }
    c.runs.unshift({ ...record, threadId: t.id });
    reply(t, 4000, '（模拟回复）定时任务「' + c.name + '」本次运行完成。');
    render();
    toast('已触发「' + c.name + '」');
  }
  function sessionOptions() {
    var out = [];
    allFolders().forEach(function (f) { sessionsIn(f).forEach(function (s) { out.push({ v: s.id, t: folderPath(f) + ' / ' + s.title }); }); });
    return out;
  }
  function threadOptions() {
    var out = [];
    eachThread(function (t, s, f) { out.push({ v: t.id, t: f.name + ' / ' + threadTitle({ t: t, s: s, f: f }) }); });
    return out;
  }
  function turnOptions(sid) {
    var x = findSession(sid);
    if (!x) { return []; }
    var out = [];
    x.s.threads.forEach(function (t) {
      var turn = 0;
      var prompt = '';
      t.msgs.forEach(function (m, index) {
        if (m.role === 'user') { turn += 1; prompt = m.text; }
        if (m.turnEnd == null) { return; }
        out.push({ v: t.id + ':' + index, t: t.name + ' · 第 ' + turn + ' 轮 · ' + clip(prompt, 22) });
      });
    });
    return out;
  }
  function fillSelect(sel, opts, value) {
    sel.innerHTML = opts.map(function (o) { return '<option value="' + esc(o.v) + '">' + esc(o.t) + '</option>'; }).join('');
    if (value != null && opts.some(function (o) { return o.v === String(value); })) { sel.value = String(value); }
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function setValue(input, value) { input.value = value; input.dispatchEvent(new Event('change', { bubbles: true })); }
  function dateStr(offsetDays) { var d = new Date(Date.now() + offsetDays * DAY); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function openSched(c) {
    var d = c || { name: '', prompt: '', action: { type: 'new', folderId: curFolderId(), agent: AGENTS[0] }, trigger: trig('repeat', 'daily', '09:00', 1) };
    SCH.edit = c || null;
    SCH.kind = d.trigger.kind;
    byId('dlg-sched-title').textContent = c ? '编辑定时任务' : '新建定时任务';
    byId('sch-delete').hidden = !c;
    var form = byId('sch-form');
    form.querySelectorAll('.field[data-invalid]').forEach(function (n) { n.removeAttribute('data-invalid'); });
    form.querySelectorAll('input, select, textarea').forEach(function (n) { n.setCustomValidity(''); n.removeAttribute('aria-invalid'); });
    byId('sch-name').value = d.name;
    byId('sch-prompt').value = d.prompt;
    form.querySelectorAll('input[name="sch-act"]').forEach(function (r) { r.checked = r.value === d.action.type; });
    SCH.syncing = true;
    fillSelect(byId('sch-folder'), allFolders().map(function (f) { return { v: f.id, t: folderPath(f) }; }), d.action.folderId || curFolderId());
    setValue(byId('sch-agent'), d.action.agent || AGENTS[0]);
    fillSelect(byId('sch-session'), sessionOptions(), d.action.sessionId);
    fillSelect(byId('sch-turn'), turnOptions(byId('sch-session').value), d.action.type === 'branch' ? d.action.threadId + ':' + d.action.endIndex : null);
    byId('sch-turn').dataset.sid = byId('sch-session').value;
    fillSelect(byId('sch-thread'), threadOptions(), d.action.threadId);
    setValue(byId('sch-freq'), d.trigger.freq);
    setValue(byId('sch-dow'), String(d.trigger.dow));
    fillSelect(byId('sch-tz'), TZS.map(function (z) { return { v: z, t: z === LOCAL_TZ ? z + '（本机）' : z }; }), d.trigger.tz);
    byId('sch-date').min = dateStr(0);
    setValue(byId('sch-date'), d.trigger.date || dateStr(1));
    SCH.syncing = false;
    setValue(byId('sch-time'), d.trigger.time);
    byId('sch-minute').value = d.trigger.minute;
    byId('sch-cron').value = d.trigger.cron;
    setValue(byId('sch-once-time'), d.trigger.onceTime);
    syncSchedForm();
    window.htmlviewUI.open('dlg-sched');
  }
  function toggleGroup(node, on) {
    node.hidden = !on;
    node.querySelectorAll('input, select, textarea').forEach(function (c) {
      if (c.disabled === !on) { return; }
      c.disabled = !on;
      if (c.tagName === 'SELECT' || c.type === 'date' || c.type === 'time') { c.dispatchEvent(new Event('change', { bubbles: true })); }
    });
  }
  function syncSchedForm() {
    if (SCH.syncing) { return; }
    SCH.syncing = true;
    var form = byId('sch-form');
    var act = form.querySelector('input[name="sch-act"]:checked').value;
    var sid = byId('sch-session').value;
    if (byId('sch-turn').dataset.sid !== sid) { fillSelect(byId('sch-turn'), turnOptions(sid), null); byId('sch-turn').dataset.sid = sid; }
    form.querySelectorAll('[data-for]').forEach(function (n) { toggleGroup(n, n.dataset.for === act); });
    form.querySelectorAll('[data-action="sch-kind"]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.kind === SCH.kind)); });
    form.querySelectorAll('[data-kind-for]').forEach(function (n) { toggleGroup(n, n.dataset.kindFor === SCH.kind); });
    var freq = byId('sch-freq').value;
    form.querySelectorAll('[data-freq-for]').forEach(function (n) { toggleGroup(n, SCH.kind === 'repeat' && n.dataset.freqFor.split(' ').indexOf(freq) >= 0); });
    SCH.syncing = false;
    updatePreview();
  }
  function readTrigger() {
    var minute = parseInt(byId('sch-minute').value, 10);
    return {
      kind: SCH.kind, freq: byId('sch-freq').value, time: byId('sch-time').value || '09:00', dow: +byId('sch-dow').value,
      minute: isNaN(minute) ? 0 : Math.min(59, Math.max(0, minute)), cron: byId('sch-cron').value.trim(),
      date: byId('sch-date').value, onceTime: byId('sch-once-time').value, tz: byId('sch-tz').value || LOCAL_TZ
    };
  }
  function updatePreview() {
    var tr = readTrigger();
    var r = evalTrigger(tr);
    var p = byId('sch-preview');
    p.classList.toggle('x-bad', !!r.error);
    if (r.error) { p.textContent = r.error.msg; return; }
    if (!r.runs.length) { p.textContent = '填好时间后，这里显示接下来的运行时间。'; return; }
    var list = r.runs.slice(0, 3).map(function (ts) { return fmtRun(ts, tr.tz); }).join('、');
    p.textContent = (tr.kind === 'once' ? '将在 ' + list + ' 运行一次' : '接下来：' + list) + '（' + tr.tz + '）';
  }
  function onSchedSubmit(e) {
    e.preventDefault();
    var form = byId('sch-form');
    var tr = readTrigger();
    var r = evalTrigger(tr);
    ['sch-cron', 'sch-once-time', 'sch-name', 'sch-prompt'].forEach(function (id) { byId(id).setCustomValidity(''); });
    if (!byId('sch-name').value.trim()) { byId('sch-name').setCustomValidity('请填写名称。'); }
    if (!byId('sch-prompt').value.trim()) { byId('sch-prompt').setCustomValidity('请填写要发送的消息。'); }
    if (r.error) {
      var bad = byId(r.error.id);
      bad.setCustomValidity(r.error.msg);
      bad.closest('.field').querySelector('.field-error').textContent = r.error.msg;
    }
    if (!form.checkValidity()) {
      // A fieldset also matches :invalid; focus the field, not its group.
      form.querySelector('input:invalid, select:invalid, textarea:invalid')?.focus();
      return;
    }
    var act = form.querySelector('input[name="sch-act"]:checked').value;
    var turn = byId('sch-turn').value.split(':');
    var action = act === 'new' ? { type: 'new', folderId: byId('sch-folder').value, agent: byId('sch-agent').value }
      : act === 'branch' ? { type: 'branch', sessionId: byId('sch-session').value, threadId: turn[0], endIndex: +turn[1] }
      : { type: 'continue', threadId: byId('sch-thread').value };
    var c = SCH.edit;
    if (!c) {
      c = { id: nid('c'), enabled: true, runs: [] };
      M.schedules.push(c);
    }
    c.name = byId('sch-name').value.trim();
    c.prompt = byId('sch-prompt').value.trim();
    c.action = action;
    c.trigger = tr;
    if (tr.kind === 'once') { c.enabled = true; }
    window.htmlviewUI.close();
    render();
    toast((SCH.edit ? '已保存「' : '已创建定时任务「') + c.name + '」');
  }
  function askDeleteSchedule(c) {
    // Close the editor first so the confirmation returns focus to the task's menu button.
    window.htmlviewUI.close();
    confirmDlg({
      title: '删除定时任务「' + c.name + '」？',
      body: '<p>任务和它的执行记录会被删除；已经产生的会话和 Thread 保留。</p>',
      ok: '删除任务',
      onOk: function () {
        M.schedules.splice(M.schedules.indexOf(c), 1);
        if (V.scheduleHistory === c.id) { V.scheduleHistory = null; }
        render();
        el.app.querySelector('[data-action="new-schedule"]').focus();
        toast('已删除定时任务「' + c.name + '」');
      }
    });
  }
  function schedMenu(c, anchor) {
    openMenu(anchor, [
      { label: '立即运行', icon: 'play', run: function () { runSchedule(c); } },
      { label: '编辑', icon: 'pencil', run: function () { openSched(c); } }
    ]);
  }

  /* ---------- 菜单与弹窗 ---------- */
  function openMenu(anchor, items, point, restore) {
    closeMenu(false);
    V.menuItems = items;
    V.menuAnchor = anchor;
    if (anchor.hasAttribute('aria-expanded')) { anchor.setAttribute('aria-expanded', 'true'); }
    V.menuRestore = restore || anchor;
    mountDomView(el.menu, createElement(WorkspaceMenu, { items }));
    el.menu.hidden = false;
    el.menu.removeAttribute('data-keyboard');
    el.menu.scrollTop = 0;
    var ar = el.app.getBoundingClientRect();
    var mr = el.menu.getBoundingClientRect();
    var x;
    var y;
    if (point) { x = point.x - ar.left; y = point.y - ar.top; } else {
      var r = anchor.getBoundingClientRect();
      x = r.right - ar.left - mr.width;
      y = anchor.matches('.x-account-trigger') ? r.top - ar.top - mr.height - 4 : r.bottom - ar.top + 4;
      if (x < 8) { x = r.left - ar.left; }
    }
    el.menu.style.left = Math.max(8, Math.min(x, ar.width - mr.width - 8)) + 'px';
    el.menu.style.top = Math.max(8, Math.min(y, ar.height - mr.height - 8)) + 'px';
    var first = el.menu.querySelector('[aria-current="true"]') || el.menu.querySelector('.x-menu-item:not([aria-disabled="true"])') || el.menu.querySelector('.x-menu-item');
    if (first) { first.focus(); }
  }
  function closeMenu(restore) {
    if (el.menu.hidden) { return; }
    el.menu.hidden = true;
    if (V.menuAnchor?.hasAttribute('aria-expanded')) { V.menuAnchor.setAttribute('aria-expanded', 'false'); }
    if (restore && V.menuRestore) {
      if (V.menuRestore.isConnected) V.menuRestore.focus();
      else if (V.menuRestore.dataset.fkey) focusKey(V.menuRestore.dataset.fkey);
    }
    V.menuAnchor = null;
    V.menuRestore = null;
    V.menuEditing = null;
    el.menu.style.width = '';
  }
  function startMenuRename(index, item) {
    V.menuEditing = index;
    el.menu.style.width = el.menu.offsetWidth + 'px';
    mountDomView(el.menu, createElement(WorkspaceMenu, { items: V.menuItems, editing: {
      index,
      save: (name) => {
        const error = item.rename(name);
        if (error) return error;
        item.label = name;
        finishMenuRename();
        return '';
      },
    } }));
  }
  function finishMenuRename() {
    const index = V.menuEditing;
    V.menuEditing = null;
    mountDomView(el.menu, createElement(WorkspaceMenu, { items: V.menuItems }));
    el.menu.querySelector('[data-mi="' + index + '"]')?.focus();
  }
  function openItem(target) {
    return { label: '打开', key: 'Enter', run: (anchor) => requestOpen(target, anchor) };
  }
  function rowMenuItems(key, statusView) {
    var k = key.charAt(0);
    var id = key.slice(2);
    var rename = { label: '重命名', icon: 'pencil', key: statusView ? null : 'r', run: function () { startRename(key, statusView); } };
    if (k === 'f') {
      var f = findFolder(id);
      var why = folderDeleteBlock(f);
      return [
        { label: '新建会话', icon: 'plus', run: (anchor) => requestOpen({ k: 'draft', folderId: f.id }, anchor) },
        { label: '新建画布', icon: 'canvas', run: (anchor) => newCanvas(f.id, anchor) },
        { label: '新建子目录', icon: 'folderPlus', run: () => newFolder(f) },
        { sep: true }, rename, { sep: true },
        { label: '删除目录', icon: 'trash', key: 'd', danger: !why, disabled: !!why, note: why, run: function () { askDeleteFolder(f); } }];
    }
    if (k === 's' || k === 'b') {
      var canvas = k === 'b';
      var items = canvas ? [openItem({ k: 'canvas', id }), { sep: true }] : [];
      return items.concat([rename, { sep: true },
        { label: canvas ? '删除画布' : '删除会话', icon: 'trash', danger: true, key: 'd', run: function () { askDelete(key); } }]);
    }
    var y = findThread(id);
    return [openItem({ k: 'thread', id: id }), { sep: true }, rename, { sep: true },
      { label: y.s.threads.length === 1 ? '删除会话' : '删除 Thread', icon: 'trash', danger: true, key: statusView ? null : 'd', run: function () { askDeleteThread(y.s, y.t); } }];
  }
  function openRowMenu(key, point) {
    if (V.sel !== key) { select(key); renderSide(); }
    var row = el.tree.querySelector('[data-key="' + key + '"]');
    if (!row) { return; }
    openMenu(row.querySelector('.x-more'), rowMenuItems(key), point, row);
  }
  function tvMenu(tid, anchor, point) {
    openMenu(anchor, rowMenuItems(threadKey(tid), true).concat([{ sep: true },
      { label: '在目录中显示', icon: 'folder', run: function () { reveal(tid); } }]), point);
  }
  function openAccountMenu(anchor, changePassword) {
    if (V.menuAnchor === anchor || V.menuClosedFrom === anchor) {
      closeMenu(true);
      V.menuClosedFrom = null;
      return;
    }
    openMenu(anchor, [
      { label: '修改密码', icon: 'keyRound', run: changePassword },
      { label: '退出登录', icon: 'logOut', run: logout },
    ]);
  }
  function logout() {
    const drafts = chatSpaces.flatMap((space) => [...space.targets.values()])
      .filter((target) => target.k === 'draft' && target.text);
    const finish = () => {
      drafts.forEach((target) => { target.text = ''; });
      workspaceUserId = null;
      setSessionUser(null);
      window.htmlviewUI.go('scr-login');
    };
    if (drafts.length) {
      confirmDlg({ title: '退出登录', body: '未发送的内容会丢失。', ok: '退出登录', onOk: finish });
    } else { finish(); }
  }
  function chatSpaceMenuItems() {
    const active = chatSpaces.filter((space) => space.archivedAt === null);
    const archived = chatSpaces.filter((space) => space.archivedAt !== null);
    const items = active.map((space) => ({
      label: space.title,
      current: space === chatSpace,
      run: space === chatSpace ? undefined : () => switchChatSpace(space),
      rename: (name) => {
        if (!name) return '请输入空间名称。';
        const error = chatSpaceNameError(name, space);
        if (error) return error;
        space.title = name;
        if (space === chatSpace) el.chatSpaceName.textContent = name;
        renderThreadsView();
        return '';
      },
    }));
    items.push({ sep: true },
      { label: 'Chat Space', accessibleLabel: '新建 Chat Space', icon: 'plus', run: openChatSpaceForm });
    const next = active.find((space) => space !== chatSpace);
    if (chatSpace.archivedAt === null && next) items.push({ label: '归档当前空间', icon: 'archive', run: () => {
      chatSpace.archivedAt = Date.now();
      switchChatSpace(next);
    } });
    if (archived.length) items.push({ label: '已归档', icon: 'archiveRestore', run: (anchor) => openMenu(anchor, [
      { label: '返回', icon: 'back', run: () => openMenu(anchor, chatSpaceMenuItems()) }, { sep: true },
      ...archived.map((space) => ({ label: `恢复「${space.title}」`, icon: 'archiveRestore', run: () => {
        space.archivedAt = null;
        switchChatSpace(space);
      } })),
    ]) });
    return items;
  }
  function chatSpaceNameError(name, space) {
    return chatSpaces.some((other) => other !== space && other.title.toLowerCase() === name.toLowerCase())
      ? '已有同名空间。' : '';
  }
  function openChatSpaceForm() {
    askName({
      title: '新建 Chat Space', label: '空间名称', value: '',
      validate: (name) => chatSpaceNameError(name),
      onConfirm: (name) => {
        const created = emptyData(name);
        chatSpaces.push(created);
        switchChatSpace(created);
      },
    });
  }
  function askName({ title, label, value, submit = '创建', normalize = (name) => name.trim(), validate, onConfirm }) {
    pendingName = { label, normalize, validate, onConfirm };
    byId('dlg-name-title').textContent = title;
    byId('name-label').textContent = label;
    byId('name-submit').textContent = submit;
    const input = byId('name-value');
    input.value = value;
    input.setCustomValidity('');
    window.htmlviewUI.open('dlg-name');
    input.select();
  }
  function submitName(e) {
    e.preventDefault();
    const input = byId('name-value');
    const { label, normalize, validate, onConfirm } = pendingName;
    const name = normalize(input.value);
    const error = !name ? `请输入${label}。` : validate?.(name);
    if (error) { input.setCustomValidity(error); input.reportValidity(); return; }
    window.htmlviewUI.close();
    onConfirm(name);
  }
  function confirmDlg(opts) {
    byId('dlg-msg-title').textContent = opts.title;
    byId('dlg-msg-body').innerHTML = opts.body;
    var ok = byId('dlg-msg-ok');
    ok.hidden = !opts.onOk;
    ok.textContent = opts.ok || '确定';
    byId('dlg-msg-cancel').textContent = opts.onOk ? '取消' : '知道了';
    V.onOk = opts.onOk || null;
    window.htmlviewUI.open('dlg-msg');
  }

  /* ---------- 目录树键盘、拖拽 ---------- */
  function activateRow(key, anchor) {
    var r = V.rows.find(function (x) { return rowKey(x) === key; });
    if (!r) { return; }
    if (r.kind === 'folder' || r.kind === 'session') {
      select(key);
      if (r.kind === 'folder') { if (r.expandable) { r.f.open = !r.open; } } else { r.s.open = !r.open; }
      renderSide();
      focusKey('row:' + key);
      return;
    }
    requestOpen(r.kind === 'canvas' ? { k: 'canvas', id: r.canvas.id } : { k: 'thread', id: r.t.id }, anchor);
  }
  function moveFocus(i) {
    var r = V.rows[i];
    if (!r) { return; }
    select(rowKey(r));
    renderSide();
    focusKey('row:' + rowKey(r));
  }
  function onTreeKey(e) {
    if (e.defaultPrevented || e.isComposing || e.keyCode === 229 || !el.menu.hidden || isDialogOpen()) { return; }
    var rowEl = e.target;
    if (!rowEl.matches('.x-row')) { return; }
    var menu = e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey && e.key === 'F10';
    if ((e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) && !menu) { return; }
    var key = rowEl.dataset.key;
    var i = V.rows.findIndex(function (x) { return rowKey(x) === key; });
    var r = V.rows[i];
    if (!r) { return; }
    switch (e.key) {
      case 'j': case 'ArrowDown': moveFocus(i + 1); break;
      case 'k': case 'ArrowUp': moveFocus(i - 1); break;
      case 'l': case 'ArrowRight':
        if (r.expandable && !r.open) { activateRow(key); }
        else if (r.expandable && V.rows[i + 1] && V.rows[i + 1].level > r.level) { moveFocus(i + 1); }
        else { return; }
        break;
      case 'h': case 'ArrowLeft':
        if (r.expandable && r.open) { activateRow(key); } else {
          for (var j = i - 1; j >= 0; j--) { if (V.rows[j].level < r.level) { moveFocus(j); break; } }
        }
        break;
      case 'Home': moveFocus(0); break;
      case 'End': moveFocus(V.rows.length - 1); break;
      case 'Enter': case ' ': activateRow(key, rowEl); break;
      case 'r': case 'F2': startRename(key); break;
      case 'd': case 'Delete': askDelete(key); break;
      case 'ContextMenu': openRowMenu(key); break;
      case 'F10': if (e.shiftKey) { openRowMenu(key); } else { return; } break;
      default: return;
    }
    e.preventDefault();
  }
  function askDelete(key) {
    var k = key.charAt(0);
    var id = key.slice(2);
    if (k === 'f') { askDeleteFolder(findFolder(id)); }
    if (k === 's') { askDeleteSession(findSession(id).s); }
    if (k === 'b') { askDeleteCanvas(findResource(id)); }
    if (k === 't') { var x = findThread(id); askDeleteThread(x.s, x.t); }
  }
  function dropTarget(dragKey, row, clientY) {
    var key = row.dataset.key || '';
    var rect = row.getBoundingClientRect();
    var pos = clientY < rect.top + rect.height / 2 ? 'before' : 'after';
    if (dragKey.startsWith('f:')) {
      if (!key.startsWith('f:') || key === dragKey) { return null; }
      var from = findFolder(dragKey.slice(2));
      var to = findFolder(key.slice(2));
      var edge = (clientY - rect.top) / rect.height;
      if (edge < 0.25 || edge > 0.75) {
        const parent = parentFolder(to);
        return parent === parentFolder(from) || canMoveFolder(from, parent) ? { key, pos } : null;
      }
      return canMoveFolder(from, to) ? { pos: 'into', folderId: to.id } : null;
    }
    if ((key.startsWith('s:') || key.startsWith('b:')) && key !== dragKey) { return { key: key, pos: pos }; }
    if (key.charAt(0) === 'f') { return { pos: 'into', folderId: key.slice(2) }; }
    return null;
  }
  function applyDrop(dragKey, t) {
    var id = dragKey.slice(2);
    if (dragKey.charAt(0) === 'f') {
      var f = findFolder(id);
      var siblings = siblingsOf(f);
      siblings.splice(siblings.indexOf(f), 1);
      if (t.pos === 'into') {
        var parent = findFolder(t.folderId);
        parent.folders.push(f);
        expandTo(parent);
      } else {
        const anchor = findFolder(t.key.slice(2));
        const list = siblingsOf(anchor);
        list.splice(list.indexOf(anchor) + (t.pos === 'after' ? 1 : 0), 0, f);
      }
    } else {
      var x = findResource(id);
      x.f.resources.splice(x.f.resources.indexOf(x.resource), 1);
      if (t.pos === 'into') {
        var target = findFolder(t.folderId);
        target.resources.unshift(x.resource);
        expandTo(target);
      } else {
        var y = findResource(t.key.slice(2));
        y.f.resources.splice(y.f.resources.indexOf(y.resource) + (t.pos === 'after' ? 1 : 0), 0, x.resource);
      }
    }
    select(dragKey);
    render();
  }

  /* ---------- 事件绑定 ---------- */
  function onClick(e) {
    var b = e.target.closest('[data-action]');
    if (!b || !el.screen.contains(b)) { return; }
    if (b === V.menuClosedFrom) { V.menuClosedFrom = null; return; }
    var pane = b.closest('.x-pane');
    var leafId = pane ? pane.dataset.leaf : null;
    if (b.getAttribute('aria-disabled') === 'true') { if (b.title) { toast(b.title); } return; }
    switch (b.dataset.action) {
      case 'chat-space-menu': openMenu(b, chatSpaceMenuItems()); break;
      case 'side-collapse': V.sideOff = true; render(); el.rail.querySelector('button').focus(); break;
      case 'side-expand': V.sideOff = false; render(); break;
      case 'drawer-open': openDrawer(); break;
      case 'drawer-close': closeDrawer(); break;
      case 'new-root-folder': newFolder(); break;
      case 'new-schedule': openSched(null); break;
      case 'row-more': openRowMenu(b.closest('.x-row').dataset.key); break;
      case 'tv-open': openStatusThread(b.closest('.x-trow').dataset.tid, b); break;
      case 'tv-menu': tvMenu(b.closest('.x-trow').dataset.tid, b); break;
      case 'group-toggle': V.groups[b.dataset.st] = !V.groups[b.dataset.st]; renderThreadsView(); focusKey('grp:' + b.dataset.st); break;
      case 'sch-menu': schedMenu(findSched(b.closest('.x-sch').dataset.sch), b); break;
      case 'sch-history': toggleScheduleHistory(findSched(b.closest('.x-sch').dataset.sch)); break;
      case 'sch-newer': case 'sch-older': turnSchedulePage(findSched(b.closest('.x-sch').dataset.sch), b.dataset.action === 'sch-newer' ? -1 : 1); break;
      case 'sch-open-run': openScheduleRun(findSched(b.closest('.x-sch').dataset.sch), b); break;
      case 'turn-new-session': askBranchName(leafId, Number(b.dataset.turnEndIndex), true); break;
      case 'dlg-ok': { var fn = V.onOk; V.onOk = null; window.htmlviewUI.close(); if (fn) { fn(); } break; }
      case 'sch-kind': SCH.kind = b.dataset.kind; syncSchedForm(); break;
      case 'sch-delete': askDeleteSchedule(SCH.edit); break;
      default: return;
    }
  }
  function bind() {
    el.screen.addEventListener('click', onClick);

    document.addEventListener('pointerdown', function (e) {
      V.menuClosedFrom = null;
      if (!el.menu.hidden && !el.menu.contains(e.target)) {
        if (V.menuAnchor && V.menuAnchor.contains(e.target)) { V.menuClosedFrom = V.menuAnchor.closest('[data-action]') || V.menuAnchor; }
        const accountMenu = V.menuAnchor?.matches('.x-account-trigger');
        if (accountMenu) e.preventDefault(); // Keep the restored avatar focus on outside pointer presses.
        closeMenu(accountMenu || V.menuEditing != null);
      }
    }, true);
    document.addEventListener('keydown', function (e) {
      if (!M || e.defaultPrevented || e.isComposing || e.keyCode === 229 || isDialogOpen() || !el.app.contains(e.target)) { return; }
      if (e.key !== 'Escape') { return; }
      if (!el.menu.hidden) { e.preventDefault(); closeMenu(true); }
      else if (V.drawer) { closeDrawer(); }
      else if (V.fullscreen) { e.preventDefault(); toggleFullscreen(L.focus); }
    });

    el.menu.addEventListener('pointermove', () => el.menu.removeAttribute('data-keyboard'));
    el.menu.addEventListener('click', function (e) {
      var b = e.target.closest('.x-menu-item, .x-menu-space-edit');
      if (!b) { return; }
      var it = V.menuItems[+b.dataset.mi];
      if (it.disabled) { return; }
      if (it.rename && (b.matches('.x-menu-space-edit') || !it.run)) { startMenuRename(+b.dataset.mi, it); return; }
      const anchor = V.menuRestore;
      closeMenu(it.restoresFocus !== false);
      it.run(anchor);
    });
    el.menu.addEventListener('keydown', function (e) {
      if (e.isComposing || e.keyCode === 229) { return; }
      if (V.menuEditing != null && e.target.closest('.x-menu-rename')) {
        if (e.key === 'Tab') { closeMenu(true); }
        else if (e.key === 'Escape') {
          e.preventDefault();
          e.stopPropagation();
          finishMenuRename();
        }
        return;
      }
      if (e.key === 'F2' && e.target.matches('.x-menu-item')) {
        const index = Number(e.target.dataset.mi);
        const item = V.menuItems[index];
        if (item.rename && !item.disabled) {
          e.preventDefault();
          el.menu.setAttribute('data-keyboard', '');
          startMenuRename(index, item);
          return;
        }
      }
      var shortcut = !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey && /^[a-z]$/.test(e.key) ? e.key : null;
      var item = shortcut && V.menuItems.find(function (it) { return it.key === shortcut && !it.disabled; });
      if (item) {
        e.preventDefault();
        e.stopPropagation();
        const anchor = V.menuRestore;
        closeMenu(item.restoresFocus !== false);
        item.run(anchor);
        return;
      }
      var items = Array.prototype.slice.call(el.menu.querySelectorAll('.x-menu-item'));
      var i = items.findIndex((item) => item.dataset.mi === document.activeElement?.dataset.mi);
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) el.menu.setAttribute('data-keyboard', '');
      if (e.key === 'ArrowDown') { items[(i + 1) % items.length].focus(); } else if (e.key === 'ArrowUp') { items[(i - 1 + items.length) % items.length].focus(); } else if (e.key === 'Home') { items[0].focus(); } else if (e.key === 'End') { items[items.length - 1].focus(); } else if (e.key === 'Escape') { closeMenu(true); } else if (e.key === 'Tab') { closeMenu(false); return; } else { return; }
      e.preventDefault();
      e.stopPropagation();
    });

    el.tree.addEventListener('click', function (e) {
      if (e.target.closest('.x-rename, [data-action]')) { return; }
      var row = e.target.closest('.x-row');
      if (row) { activateRow(row.dataset.key, row); }
    });
    el.tree.addEventListener('contextmenu', function (e) {
      var row = e.target.closest('.x-row');
      if (!row || e.target.closest('.x-rename')) { return; }
      e.preventDefault();
      openRowMenu(row.dataset.key, { x: e.clientX, y: e.clientY });
    });
    el.tree.addEventListener('keydown', onTreeKey);
    el.side.addEventListener('keydown', function (e) {
      if (!e.target.matches('.x-rename') || e.isComposing || e.keyCode === 229 || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) { return; }
      if (e.key === 'Enter') { e.preventDefault(); commitRename(); }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancelRename(); }
    });
    el.side.addEventListener('input', function (e) { if (e.target.classList.contains('x-rename')) { V.renameVal = e.target.value; } });
    el.side.addEventListener('focusout', function (e) {
      if (!e.target.classList.contains('x-rename') || !V.renaming || !e.target.isConnected) { return; }
      if (!commitRename()) { cancelRename(); }
    });
    bindTreeDrag(el.tree, { onStart: () => closeMenu(false), resolveDrop: dropTarget, onDrop: applyDrop });

    el.threads.addEventListener('contextmenu', function (e) {
      var row = e.target.closest('.x-trow');
      const more = row?.querySelector('.x-more');
      if (!more || e.target.closest('.x-rename')) { return; }
      e.preventDefault();
      tvMenu(row.dataset.tid, more, { x: e.clientX, y: e.clientY });
    });
    byId('x-filter-tree').addEventListener('input', function (e) { V.qTree = e.target.value; renderTree(); });
    byId('x-filter-threads').addEventListener('input', function (e) { V.qThreads = e.target.value; renderThreadsView(); });

    el.panes.addEventListener('compositionstart', function () { V.composing = true; });
    el.panes.addEventListener('compositionend', function () { V.composing = false; if (V.pending) { V.pending = false; render(); } });

    el.resize.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      var startX = e.clientX;
      var startW = V.sideW;
      el.resize.setPointerCapture(e.pointerId);
      el.resize.classList.add('x-drag');
      var move = function (ev) { setSideW(startW + ev.clientX - startX); };
      var up = function () {
        el.resize.classList.remove('x-drag');
        el.resize.removeEventListener('pointermove', move);
        el.resize.removeEventListener('pointerup', up);
        el.resize.removeEventListener('pointercancel', up);
        if (computeModes()) { render(); }
      };
      el.resize.addEventListener('pointermove', move);
      el.resize.addEventListener('pointerup', up);
      el.resize.addEventListener('pointercancel', up);
    });
    el.resize.addEventListener('keydown', function (e) {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') { return; }
      e.preventDefault();
      setSideW(V.sideW + (e.key === 'ArrowLeft' ? -16 : 16));
      if (computeModes()) { render(); }
    });

    byId('name-value').addEventListener('input', function (e) { e.target.setCustomValidity(''); });
    byId('name-form').addEventListener('submit', submitName);
    byId('dlg-name').addEventListener('close', ({ currentTarget }) => { if (!currentTarget.open) { pendingName = undefined; } });
    byId('canvas-name').addEventListener('input', function (e) { e.target.setCustomValidity(''); });
    byId('canvas-form').addEventListener('submit', createCanvas);
    var form = byId('sch-form');
    form.querySelectorAll('.field').forEach(function (field) {
      var input = field.querySelector('input[id], select[id], textarea[id]');
      if (!input) { return; }
      var descriptions = Array.from(field.querySelectorAll('.field-help, .field-error'));
      descriptions.forEach(function (element, index) { if (!element.id) { element.id = input.id + '-description-' + index; } });
      if (descriptions.length) { input.setAttribute('aria-describedby', descriptions.map(function (n) { return n.id; }).join(' ')); }
    });
    form.addEventListener('change', function () { if (!SCH.syncing) { syncSchedForm(); } });
    form.addEventListener('input', function (e) { e.target.setCustomValidity(''); if (!SCH.syncing) { updatePreview(); } });
    form.addEventListener('submit', onSchedSubmit);
  }
  function setSideW(w) {
    V.sideW = Math.max(220, Math.min(480, w));
    el.app.style.setProperty('--x-side-w', V.sideW + 'px');
    el.resize.setAttribute('aria-valuenow', String(V.sideW));
  }

  /* ---------- 启动与状态切换 ---------- */
  function syncScheme() { el.app.classList.toggle('x-dark', document.documentElement.getAttribute('data-scheme') === 'dark'); }
  /* 导出和打印时 html-view 用静态副本替换屏幕，原型逻辑只在原始屏幕存在时运行。 */
  function init() {
    if (ready) { return true; }
    if (!byId('scr-work')) { return false; }
    ready = true;
    ['screen:scr-work', 'app:x-app', 'main:x-main', 'side:x-side', 'tree:x-tree', 'threads:x-threads', 'sched:x-sched', 'panes:x-panes', 'storage:x-pane-storage', 'mobileView:x-mobile-view', 'menu:x-menu', 'scrim:x-scrim', 'rail:x-rail', 'resize:x-side-resize', 'chatSpaceName:x-chat-space-name'].forEach(function (pair) {
      var p = pair.split(':');
      el[p[0]] = byId(p[1]);
    });
    var controls = { icon: ['icon-button'], iconCompact: ['icon-button', 'is-compact'], ghost: ['btn-secondary'],
      ghostCompact: ['btn-secondary', 'is-compact'], ghostDanger: ['btn-secondary', 'danger'], primary: ['btn-primary'], danger: ['btn-primary', 'danger'] };
    el.screen.querySelectorAll('[data-control]').forEach(function (n) { n.classList.add(...controls[n.dataset.control]); });
    el.screen.querySelectorAll('[data-ic]').forEach(function (n) { n.innerHTML = svg(n.getAttribute('data-ic')); });
    paneView = createPaneLayoutView({ panes: el.panes, storage: el.storage, mobileHeader: el.mobileView,
      onAction: onPaneAction, onFocus: setFocusLeaf, onSwap: swapLeaves });
    bind();
    syncScheme();
    new MutationObserver(syncScheme).observe(document.documentElement, { attributes: true, attributeFilter: ['data-scheme'] });
    var workspaceResize = new ResizeObserver(function () {
      if (!M || !el.app.isConnected || !el.app.clientWidth) { return; }
      if (computeModes()) { render(); } else { paneView.updateSplitButtons(); }
    });
    workspaceResize.observe(el.app);
    workspaceResize.observe(el.panes);
    return true;
  }
  function switchChatSpace(space, enteringUser = false) {
    if (space.archivedAt === null) activeByUser.set(workspaceUserId, space);
    if (!enteringUser && space === chatSpace) { return; }
    closeMenu(false);
    if (V.drawer) { closeDrawer(); }
    chatSpace = space;
    M = space.model;
    L = space.layout;
    targets = space.targets;
    V = { sideW: V.sideW, narrow: V.narrow, sel: null, tabKey: null, renaming: null, renameVal: '', qTree: '', qThreads: '', groups: { needs: true, running: true, waiting: true, error: true, idle: false }, rows: [] };
    byId('x-filter-tree').value = '';
    byId('x-filter-threads').value = '';
    const directoryTab = el.side.querySelector('[data-tab="x-view-tree"]');
    if (directoryTab.getAttribute('aria-selected') === 'false') { directoryTab.click(); }
    select('f:' + M.folders[0].id);
    render();
  }
  document.addEventListener('htmlview:change', function (e) {
    if (e.detail.screen === 'scr-work') {
      const { userId } = getSession();
      if (!userId) { window.htmlviewUI.go('scr-login'); return; }
      if (workspaceUserId !== userId && init() && el.app.isConnected) {
        workspaceUserId = userId;
        if (!spacesByUser.has(userId)) spacesByUser.set(userId, [emptyData('我的空间')]);
        chatSpaces = spacesByUser.get(userId);
        switchChatSpace(activeByUser.get(userId) ?? chatSpaces[0], true);
      }
    } else if (ready) { closeMenu(false); }
  });
  let previousSession = getSession();
  subscribeSession(() => {
    const { users, userId } = getSession();
    const disabled = users.filter((user) => user.disabled && previousSession.users.some((entry) => entry.id === user.id && !entry.disabled));
    for (const user of disabled) {
      for (const space of spacesByUser.get(user.id) ?? []) {
        for (const schedule of space.model.schedules) schedule.enabled = false;
      }
    }
    const revoked = userId === null && disabled.some((user) => user.id === previousSession.userId);
    previousSession = getSession();
    if (revoked) {
      workspaceUserId = null;
      window.htmlviewUI.go('scr-login');
    }
  });
  return { openEnvironment, openAccountMenu };
}
