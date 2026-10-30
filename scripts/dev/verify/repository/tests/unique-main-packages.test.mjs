import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const script = fileURLToPath(new URL('../check.mjs', import.meta.url))

function diagnostics(modules) {
  const root = mkdtempSync(path.join(tmpdir(), 'kk-main-packages-'))
  function write(name, source) {
    const file = path.join(root, name)
    mkdirSync(path.dirname(file), { recursive: true })
    writeFileSync(file, source)
  }
  try {
    write('pom.xml', `<project><modules>${Object.keys(modules).map(
      (name) => `<module>${name}</module>`,
    ).join('')}</modules></project>`)
    for (const [module, files] of Object.entries(modules)) {
      write(`${module}/pom.xml`, '<project/>')
      for (const [name, source] of Object.entries(files)) {
        write(`${module}/${name}`, source)
      }
    }
    // A minimal fixture intentionally lacks docs; isolate this guard's diagnostics.
    const result = spawnSync(process.execPath, [script, '--root', root], { encoding: 'utf8' })
    assert.equal(result.error, undefined)
    return result.stderr.split('\n').filter((line) => line.includes('Java main package shared'))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

test('main package ownership uses declarations and counts each module once', () => {
  assert.deepEqual(diagnostics({
    first: {
      'src/main/java/one/A.java': 'package example.shared;\nclass A {}',
      'src/main/java/two/B.java': 'package example.shared;\nclass B {}',
    },
    second: { 'src/main/java/other/C.java': 'package example.shared;\nclass C {}' },
  }), ['- Java main package shared across Maven modules: example.shared (first, second)'])
})

test('test sources do not participate in main package ownership', () => {
  assert.deepEqual(diagnostics({
    first: { 'src/main/java/A.java': 'package example.shared;\nclass A {}' },
    second: { 'src/test/java/B.java': 'package example.shared;\nclass B {}' },
  }), [])
})

test('P1 exceptions are limited to the named packages and their two owners', () => {
  const files = Object.fromEntries(['', '.credential', '.resource'].map((suffix, index) => [
    `src/main/java/P${index}.java`,
    `package fun.fengwk.kkstudio.platform.plugin${suffix};\nclass P${index} {}`,
  ]))
  assert.deepEqual(diagnostics({
    'platform/plugin-api': files,
    'platform/runtime': files,
  }), [])
  assert.equal(diagnostics({
    'platform/plugin-api': files,
    'platform/runtime': files,
    third: files,
  }).length, 3)
  assert.equal(diagnostics({
    first: { 'src/main/java/A.java': 'package fun.fengwk.kkstudio.platform.plugin.other;' },
    second: { 'src/main/java/B.java': 'package fun.fengwk.kkstudio.platform.plugin.other;' },
  }).length, 1)
})
