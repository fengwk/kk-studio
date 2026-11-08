import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { ENVIRONMENT_CARD_ACTIONS } from '../ui/environment-actions.mjs'

const UI_SMOKE = readFileSync(new URL('../ui-smoke.mjs', import.meta.url), 'utf8')

function caseSource(id, nextId) {
  const start = UI_SMOKE.indexOf(`await run('${id}'`)
  const end = UI_SMOKE.indexOf(`await run('${nextId}'`, start + 1)
  return start >= 0 && end > start ? UI_SMOKE.slice(start, end) : ''
}

const ENVIRONMENT_CASE = caseSource('ui.environments.page_loads', 'ui.canvas.page_loads')
const ACTION_NAMES = [
  'Install',
  'Uninstall',
  'Edit',
  'Manage',
  'Update Daemon',
  'Rotate Token',
  'Delete',
]

test('environment card inventory pins exactly the seven rendered actions including Edit', () => {
  // 测试意图：前端已新增编辑入口；E2E inventory 必须精确为七项，名称不得重复或回退为旧六项。
  assert.deepEqual(ENVIRONMENT_CARD_ACTIONS.map(([name]) => name), ACTION_NAMES)
  assert.equal(new Set(ACTION_NAMES).size, 7)
})

test('environment action patterns match real labels without cross-matching Install and Uninstall', () => {
  // 测试意图：中文“卸载”不含“安装”，英文 Uninstall 也不含大写 Install；安装模式不得误命中卸载。
  const patterns = new Map(ENVIRONMENT_CARD_ACTIONS)
  assert.match('安装 / 覆盖', patterns.get('Install'))
  assert.doesNotMatch('卸载', patterns.get('Install'))
  assert.doesNotMatch('Uninstall', patterns.get('Install'))
  assert.match('卸载', patterns.get('Uninstall'))
  assert.match('编辑配置', patterns.get('Edit'))
  assert.match('更新 Daemon', patterns.get('Update Daemon'))
  assert.match('重新生成 Token', patterns.get('Rotate Token'))
  assert.match('删除环境', patterns.get('Delete'))
})

test('ui-smoke consumes the shared environment action inventory instead of a stale inline count', () => {
  // 测试意图：真实页面断言必须引用同一 inventory，避免代码改了七项而 smoke 仍硬编码旧数量。
  assert.ok(ENVIRONMENT_CASE, 'ui-smoke must still register the environments page case')
  assert.match(UI_SMOKE, /import \{ ENVIRONMENT_CARD_ACTIONS \} from '\.\/ui\/environment-actions\.mjs'/)
  assert.match(ENVIRONMENT_CASE, /actions\.count\(\) === ENVIRONMENT_CARD_ACTIONS\.length/)
  assert.doesNotMatch(ENVIRONMENT_CASE, /actions\.count\(\) === 6/)
})
