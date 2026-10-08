import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../ui/composer-matrix.mjs', import.meta.url), 'utf8')
const caseSource = (id) => {
  const section = source.split('\n  await run(').find((value) => value.includes(`'${id}'`))
  assert.ok(section, `missing UI case ${id}`)
  return section
}

// 守住真实服务 UI runner 的接线；渲染、焦点和滚动语义另由离线 Chromium 验证。
test('conversation switch uses the sole host exit while its composer remains mounted and hidden', () => {
  const section = caseSource('ui.chat.debug.conversation_switch')
  assert.match(section, /name: '关闭 Debug', exact: true.*\.click\(\)/)
  assert.match(section, /composer\.count\(\).*composer\.isVisible\(\)/)
  assert.match(section, /#chat-layout-select/)
  assert.ok(section.indexOf('composer.fill(draft)') < section.indexOf("const listbox ="))
  assert.match(section, /await expectStorage\(page, draftKey, draft\)/)
  assert.doesNotMatch(section, /thread-debug-back|thread-debug-toolbar/)
})

test('keyboard case consumes inner Escape before exiting Debug and restoring editable focus', () => {
  const section = caseSource('ui.chat.debug.keyboard_nav')
  assert.equal((section.match(/listbox\.press\('Escape'\)/g) ?? []).length, 2)
  assert.match(section, /inner Escape also exited Debug/)
  assert.match(section, /document\.activeElement.*composer-editor/)
})

test('scroll regression only opens debug through the composer and closes through the host', () => {
  const section = caseSource('ui.chat.debug.scroll_restore')
  assert.equal((section.match(/name: '关闭 Debug', exact: true.*\.click\(\)/g) ?? []).length, 2)
  assert.equal((section.match(/await openMenuOption\('debug'\)/g) ?? []).length, 1)
  assert.match(section, /Math\.abs\(restoredEvents - eventsScrollTop\) <= 2/)
  assert.match(section, /restoredDialogueMetrics\.maxScrollTop - restoredDialogueMetrics\.scrollTop > 210/)
})
