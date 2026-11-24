const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
const LOCAL_TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const TZS = [...new Set([LOCAL_TZ, 'Asia/Shanghai', 'UTC', 'Asia/Tokyo', 'Europe/London', 'America/Los_Angeles'])];
function pad(n) { return (n < 10 ? '0' : '') + n; }
function stamp(ts) { var d = new Date(ts); return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()); }

var fmtCache = {};
var WD = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
function tzParts(ts, tz) {
  var f = fmtCache[tz] || (fmtCache[tz] = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', weekday: 'short' }));
  var p = {};
  f.formatToParts(new Date(ts)).forEach(function (x) { p[x.type] = x.value; });
  return { y: +p.year, m: +p.month, d: +p.day, h: +p.hour % 24, mi: +p.minute, dow: WD[p.weekday] };
}
function zonedToUtc(y, m, d, h, mi, tz) {
  var want = Date.UTC(y, m - 1, d, h, mi);
  var guess = want;
  for (var i = 0; i < 3; i++) {
    var p = tzParts(guess, tz);
    var diff = want - Date.UTC(p.y, p.m - 1, p.d, p.h, p.mi);
    if (!diff) { return guess; }
    guess += diff;
  }
  return null;
}
function fmtRun(ts, tz, includeYear = false) {
  const p = tzParts(ts, tz);
  return `${includeYear ? p.y + '-' : ''}${pad(p.m)}-${pad(p.d)} ${WEEK[p.dow]} ${pad(p.h)}:${pad(p.mi)}`;
}
var CRON_FIELDS = [
  { name: '分', min: 0, max: 59 }, { name: '时', min: 0, max: 23 }, { name: '日', min: 1, max: 31 },
  { name: '月', min: 1, max: 12 }, { name: '周', min: 0, max: 7 }
];
function parseField(text, f) {
  var set = {};
  text.split(',').forEach(function (part) {
    var m = /^(\*|\d+(?:-\d+)?)(?:\/(\d+))?$/.exec(part);
    if (!m) { throw new Error('「' + f.name + '」写法不对：' + part); }
    var step = m[2] ? +m[2] : 1;
    var lo;
    var hi;
    if (m[1] === '*') { lo = f.min; hi = f.max; } else {
      var r = m[1].split('-');
      lo = +r[0];
      hi = r.length > 1 ? +r[1] : (m[2] ? f.max : lo);
    }
    if (lo < f.min || hi > f.max || lo > hi) { throw new Error('「' + f.name + '」要在 ' + f.min + '–' + f.max + ' 之间：' + part); }
    if (step < 1) { throw new Error('「' + f.name + '」的步长要大于 0：' + part); }
    for (var v = lo; v <= hi; v += step) { set[v] = true; }
  });
  return { set: set, star: text.charAt(0) === '*' };
}
function parseCron(expr) {
  var parts = String(expr || '').trim().split(/\s+/);
  if (parts.length !== 5 || !parts[0]) { throw new Error('需要 5 段：分 时 日 月 周。'); }
  var f = parts.map(function (p, i) { return parseField(p, CRON_FIELDS[i]); });
  if (f[4].set[7]) { f[4].set[0] = true; }
  var sorted = function (s) { return Object.keys(s).map(Number).sort(function (a, b) { return a - b; }); };
  return { min: sorted(f[0].set), hour: sorted(f[1].set), dom: f[2], mon: f[3].set, dow: f[4] };
}
function dayOk(spec, d, dow) {
  var a = spec.dom.set[d] === true;
  var b = spec.dow.set[dow] === true;
  if (spec.dom.star && spec.dow.star) { return true; }
  if (spec.dom.star) { return b; }
  if (spec.dow.star) { return a; }
  return a || b;
}
function nextRuns(spec, tz, from, count) {
  var out = [];
  var p = tzParts(from, tz);
  var base = Date.UTC(p.y, p.m - 1, p.d);
  for (var i = 0; i < 366 * 5 && out.length < count; i++) {
    var day = new Date(base + i * DAY);
    var y = day.getUTCFullYear();
    var mo = day.getUTCMonth() + 1;
    var d = day.getUTCDate();
    if (!spec.mon[mo] || !dayOk(spec, d, day.getUTCDay())) { continue; }
    for (var hi = 0; hi < spec.hour.length && out.length < count; hi++) {
      if (i === 0 && spec.hour[hi] < p.h) { continue; }
      for (var mi = 0; mi < spec.min.length && out.length < count; mi++) {
        if (i === 0 && spec.hour[hi] === p.h && spec.min[mi] <= p.mi) { continue; }
        var ts = zonedToUtc(y, mo, d, spec.hour[hi], spec.min[mi], tz);
        if (ts !== null && ts > from) { out.push(ts); }
      }
    }
  }
  return out;
}
function triggerCron(tr) {
  var hm = String(tr.time || '09:00').split(':');
  var h = +hm[0];
  var mi = +hm[1];
  if (tr.freq === 'hourly') { return tr.minute + ' * * * *'; }
  if (tr.freq === 'daily') { return mi + ' ' + h + ' * * *'; }
  if (tr.freq === 'weekdays') { return mi + ' ' + h + ' * * 1-5'; }
  if (tr.freq === 'weekly') { return mi + ' ' + h + ' * * ' + tr.dow; }
  return tr.cron;
}
function onceTs(tr) {
  var dm = /^(\d{4})-(\d{2})-(\d{2})$/.exec(tr.date || '');
  var tm = /^(\d{1,2}):(\d{2})/.exec(tr.onceTime || '');
  if (!dm || !tm) { return null; }
  return zonedToUtc(+dm[1], +dm[2], +dm[3], +tm[1], +tm[2], tr.tz);
}
function evalTrigger(tr) {
  if (tr.kind === 'once') {
    var ts = onceTs(tr);
    if (ts === null) { return { runs: [], error: null }; }
    if (ts <= Date.now()) { return { runs: [], error: { id: 'sch-once-time', msg: '这个时间已经过去，请选之后的时间。' } }; }
    return { runs: [ts], error: null };
  }
  if (tr.freq === 'cron' && !tr.cron) { return { runs: [], error: null }; }
  var spec;
  try { spec = parseCron(triggerCron(tr)); } catch (e) { return { runs: [], error: { id: 'sch-cron', msg: e.message } }; }
  var runs = nextRuns(spec, tr.tz, Date.now(), 60);
  if (!runs.length) { return { runs: runs, error: { id: 'sch-cron', msg: '5 年内都不会运行，请检查日期。' } }; }
  for (var i = 1; i < runs.length; i++) {
    if (runs[i] - runs[i - 1] < 5 * MIN) { return { runs: runs, error: { id: 'sch-cron', msg: '两次运行至少间隔 5 分钟。' } }; }
  }
  return { runs: runs, error: null };
}
function nextRun(c) {
  if (!c.enabled) { return null; }
  var tr = c.trigger;
  if (tr.kind === 'once') { var ts = onceTs(tr); return ts && ts > Date.now() ? ts : null; }
  var r = evalTrigger(tr);
  return r.runs[0] || null;
}

export { DAY, HOUR, MIN, WEEK, LOCAL_TZ, TZS, pad, stamp, fmtRun, evalTrigger, nextRun };
