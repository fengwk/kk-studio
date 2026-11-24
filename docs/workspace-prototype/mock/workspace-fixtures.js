import { DAY, HOUR, MIN, LOCAL_TZ, stamp } from '../workspace/schedule.js';
import { createLeaf } from '../workspace/layout.js';

const AGENTS = ['编码助手', '调研助手', '写作助手'];
let sequence = 0;
function nid(prefix) { return prefix + ++sequence; }

function u(text) { return { role: 'user', text: text }; }
function a(text, turnEnd = 'usage') { return { role: 'agent', text: text, turnEnd: turnEnd }; }
function mkThread(name, status, at, msgs, extra) {
  var t = { id: nid('t'), name: name, status: status, at: at, msgs: msgs, draft: '', pending: null };
  Object.keys(extra || {}).forEach(function (k) { t[k] = extra[k]; });
  return t;
}
function mkSession(title, threads, open, agent) { return { kind: 'session', id: nid('s'), title: title, open: !!open, agent: agent || AGENTS[0], threads: threads }; }
function mkFolder(name, resources, open) { return { id: nid('f'), name: name, open: open !== false, resources: resources, folders: [] }; }
function mkCanvas(id, title, sample) { return { kind: 'canvas', id: id, title: title, sample: sample }; }
function canvasSnapshot(c) { return c ? { id: c.id, title: c.title, sample: c.sample } : null; }
function before(ms) { return Date.now() - ms; }
/* 最近 n 次「h:m」，filter 按星期几决定当天是否算一次。 */
function pastRuns(h, m, n, filter) {
  var out = [];
  var d = new Date();
  d.setHours(h, m, 0, 0);
  if (d.getTime() > Date.now()) { d.setDate(d.getDate() - 1); }
  while (out.length < n) {
    if (filter(d.getDay())) { out.push(d.getTime()); }
    d.setDate(d.getDate() - 1);
  }
  return out;
}
function copyMsgs(list) { return list.map(function (m) { return { ...m }; }); }

function sampleData() {
  var cacheBase = [u('对比三种缓存 ID 方案对 prompt cache 命中率的影响。'), a('三种方案：每个 Thread 独立 ID、同一会话共享 ID、按账号亲和。分别在分支里验证，主线记录结论。')];
  var release = mkThread('main', 'needs', before(2 * MIN), [u('根据 git log 整理 1.3.0 发布说明，按模块分组。'), a('先读取 v1.2.7 之后的提交记录，这一步要运行一条只读命令。', null)], { pending: 'git log v1.2.7..HEAD --oneline' });
  var login = mkThread('main', 'idle', before(26 * HOUR), [u('登录后偶尔跳到空白页，帮我查原因并修复。'), a('原因是回跳地址在页面刷新后丢失。已改为写入 sessionStorage，并补了一条浏览器测试，测试通过。')]);
  var cacheMain = mkThread('main', 'idle', before(50 * MIN), copyMsgs(cacheBase).concat([u('先记录基线：每个 Thread 用独立缓存 ID 时，分支后第一次请求的命中情况。'), a('基线结果：分支后第一次请求没有命中缓存，从第二次请求开始恢复。')]));
  var cacheB = mkThread('方案 B：同会话共享缓存 ID', 'running', before(MIN), copyMsgs(cacheBase).concat([u('验证方案 B：同一会话的 Thread 共用 prompt_cache_key。'), a('正在用 gpt-6-luna 跑 6 组请求，已完成 4 组。', null)]));
  var cacheC = mkThread('方案 C：按账号亲和', 'error', before(12 * MIN), copyMsgs(cacheBase).concat([{ ...u('验证方案 C：开启 session-affinity 后再测一轮。'), modelError: '示例账号的模型试用额度已用完。' }]));
  var cpa = mkThread('main', 'idle', before(3 * DAY), [u('对比 CPA 的 round-robin 和 session-affinity 两种路由。'), a('多账号时 round-robin 会把同一会话分到不同账号；session-affinity 可以让同一会话回到原账号。')]);
  var queuedBuild = mkThread('main', 'waiting', before(5 * MIN), [u('在 build-server 复现 1024px 下的布局问题。')]);
  var daily = pastRuns(9, 0, 3, function (w) { return w !== 0 && w !== 6; });
  var d1 = mkThread('main', 'idle', daily[0] + 6 * MIN, [u('汇总过去 24 小时的 CI 结果，列出失败项和可能原因。'), a('过去 24 小时共 31 次构建，失败 2 次，都是 Maven 下载超时，重跑后通过。')]);
  var d2 = mkThread('main', 'idle', daily[1] + 5 * MIN, [u('汇总过去 24 小时的 CI 结果，列出失败项和可能原因。'), a('过去 24 小时共 27 次构建，全部通过。')]);
  var oldBase = [u('列出删除 Project 模块要改的地方。'), a('涉及 7 张表、Web 装配、前端页面、E2E 和文档。')];
  var oldMain = mkThread('main', 'idle', before(16 * DAY), copyMsgs(oldBase));
  var oldBack = mkThread('只看后端', 'idle', before(15 * DAY), copyMsgs(oldBase).concat([u('先只看后端部分。'), a('后端要改 apps/project、Web 装配和 Schema。')]));
  var r9 = mkThread('main', 'idle', before(20 * DAY), [u('汇总 R9 视觉审查发现的问题。'), a('共 1128 张截图，发现 3 处需要修的问题。')]);

  var sRelease = mkSession('整理 1.3.0 发布说明', [release], false, AGENTS[2]);
  var sCache = mkSession('Prompt cache 命中率', [cacheMain, cacheB, cacheC], true, AGENTS[1]);
  var fDefault = mkFolder('默认', [sRelease, mkSession('窄屏回归', [queuedBuild]), mkSession('修复登录后跳到空白页', [login])]);
  var fResearch = mkFolder('调研', [sCache, mkCanvas('canvas-workflow-1-3-0', '1.3.0 工作流', true), mkSession('CPA 路由策略对比', [cpa], false, AGENTS[1])]);
  var fDaily = mkFolder('日报', [mkSession('每日构建日报 · ' + stamp(daily[0]), [d1]), mkSession('每日构建日报 · ' + stamp(daily[1]), [d2])]);
  var fArchive = mkFolder('归档', [mkSession('删除 Project 模块的影响面', [oldMain, oldBack]), mkSession('R9 视觉审查记录', [r9])], false);

  fArchive.folders.push(mkFolder('界面参考', []));

  // The daily samples link to their own Threads; continue runs can share a Thread.
  const scheduleRun = (at, threadId, reason) => ({ id: nid('run'), at, timeZone: LOCAL_TZ, threadId, reason });
  const dailyHistory = [scheduleRun(daily[0], d1.id), scheduleRun(daily[1], d2.id), scheduleRun(daily[2], null)];
  const regressionHistory = pastRuns(22, 0, 12, () => true).map((at, i) => i === 2
    ? scheduleRun(at, null, '跳过：目标 Thread 尚未结束') : scheduleRun(at, cacheMain.id));
  var schedules = [
    { id: nid('c'), name: '每日构建日报', enabled: true, prompt: '汇总过去 24 小时的 CI 结果，列出失败项和可能原因。', action: { type: 'new', folderId: fDaily.id, agent: AGENTS[0] }, trigger: trig('repeat', 'weekdays', '09:00', 1), runs: dailyHistory },
    { id: nid('c'), name: '缓存命中率回归', enabled: true, prompt: '按基线流程重测一次缓存命中率，和上次结果对比。', action: { type: 'continue', threadId: cacheMain.id }, trigger: trig('repeat', 'daily', '22:00', 1), runs: regressionHistory },
    { id: nid('c'), name: '周报草稿', enabled: false, prompt: '根据本周提交整理周报草稿。', action: { type: 'branch', sessionId: sCache.id, threadId: cacheMain.id, endIndex: 1 }, trigger: trig('repeat', 'weekly', '17:00', 5), runs: [] }
  ];
  var leaf = createLeaf(nid('p'));
  return {
    title: 'KK 开发', archivedAt: null,
    model: { folders: [fDefault, fResearch, fDaily, fArchive], schedules: schedules },
    layout: { root: leaf, focus: leaf.id },
    targets: new Map([[leaf.id, { k: 'thread', id: cacheB.id }]])
  };
}
function trig(kind, freq, time, dow) {
  return { kind: kind, freq: freq, time: time, dow: dow, minute: 0, cron: '', date: '', onceTime: '09:00', tz: LOCAL_TZ };
}
function emptyData(title) {
  var leaf = createLeaf(nid('p'));
  return {
    title, archivedAt: null,
    model: { folders: [mkFolder('默认', [], true)], schedules: [] },
    layout: { root: leaf, focus: leaf.id }, targets: new Map(),
  };
}

function documentationSampleData() {
  const space = emptyData('文档整理');
  const thread = mkThread('main', 'needs', before(4 * MIN), [
    u('帮我核对文档有哪些改动。'),
    a('我会先查看文档目录的 diff。', null),
  ], { pending: 'git diff -- docs' });
  space.model.folders[0].resources.push(mkSession('核对文档变更', [thread], false, AGENTS[2]));
  return space;
}

export { sampleData, emptyData, documentationSampleData, a, u, mkThread, mkSession, mkFolder, mkCanvas, canvasSnapshot, copyMsgs, AGENTS, nid, trig };
