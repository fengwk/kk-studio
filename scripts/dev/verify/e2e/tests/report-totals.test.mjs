/** 报告计数口径契约：totals 与 By Level 表必须用同一组 pass/fail/skip 定义。 */

import assert from 'node:assert/strict'
import test from 'node:test'

import { buildSummary } from '../run-matrix.mjs'

/** 报告只读取这些参数，其余取值不影响计数。 */
const args = {
  baseUrl: 'http://127.0.0.1:18081',
  distributed: false,
  frontendUrl: '',
  real: false,
  withTools: false,
  withBranch: false,
  withCanvasStorage: false,
  withCanvasFunction: false,
  only: [],
  level: [],
}

const result = (id, level, status) => ({
  id,
  level,
  title: id,
  status,
  durationMs: 1,
  error: status === 'fail' ? 'boom' : null,
  traceback: null,
  artifactPaths: [],
})

test('test intent: totals and By Level count skip separately instead of folding it into fail', () => {
  // 合成一条 skip：当前 runner 不产生该状态，但报告口径必须自洽。
  const results = [
    result('l1-pass', 'L1', 'pass'),
    result('l1-fail', 'L1', 'fail'),
    result('l1-skip', 'L1', 'skip'),
    result('l2-pass', 'L2', 'pass'),
  ]
  const { summary, report } = buildSummary('run-1', args, results, 'start', 'end', 1)

  assert.deepEqual(summary.totals.byLevel.L1, { total: 3, pass: 1, fail: 1, skip: 1 })
  assert.deepEqual(summary.totals.byLevel.L2, { total: 1, pass: 1, fail: 0, skip: 0 })
  assert.equal(summary.totals.total, 4)
  assert.equal(summary.totals.pass, 2)
  assert.equal(summary.totals.fail, 1)
  assert.equal(summary.totals.skip, 1)
  assert.deepEqual(summary.failedCaseIds, ['l1-fail'])

  // Markdown 的 Totals 行与 By Level 行必须复现同一口径。
  assert.match(report, /^- Totals: total=4 pass=2 fail=1 skip=1$/mu)
  assert.match(report, /^\| Level \| Total \| Pass \| Fail \| Skip \|$/mu)
  assert.match(report, /^\| L1 \| 3 \| 1 \| 1 \| 1 \|$/mu)
  assert.match(report, /^\| L2 \| 1 \| 1 \| 0 \| 0 \|$/mu)
})
