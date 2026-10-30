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
    return result.stderr
      .split('\n')
      .filter((line) => /Java (?:main|test) package shared across Maven modules/u.test(line))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

test('main and test package ownership are checked independently', () => {
  assert.deepEqual(diagnostics({
    first: {
      'src/main/java/one/A.java': 'package example.shared;\nclass A {}',
      'src/main/java/two/B.java': 'package example.shared;\nclass B {}',
      'src/test/java/one/ATest.java': 'package example.test.shared;\nclass ATest {}',
    },
    second: {
      'src/main/java/other/C.java': 'package example.shared;\nclass C {}',
      'src/test/java/other/CTest.java': 'package example.test.shared;\nclass CTest {}',
    },
  }), [
    '- Java main package shared across Maven modules: example.shared (first, second)',
    '- Java test package shared across Maven modules: example.test.shared (first, second)',
  ])
})

test('one module may reuse a package between main and test', () => {
  assert.deepEqual(diagnostics({
    first: {
      'src/main/java/A.java': 'package example.shared;\nclass A {}',
      'src/test/java/ATest.java': 'package example.shared;\nclass ATest {}',
    },
    second: {
      'src/main/java/B.java': 'package example.other;\nclass B {}',
      'src/test/java/BTest.java': 'package example.other;\nclass BTest {}',
    },
  }), [])
})

test('main and test packages in different modules do not conflict', () => {
  assert.deepEqual(diagnostics({
    first: { 'src/main/java/A.java': 'package example.shared;\nclass A {}' },
    second: { 'src/test/java/B.java': 'package example.shared;\nclass B {}' },
  }), [])
})

test('package ownership counts each module once', () => {
  assert.deepEqual(diagnostics({
    first: {
      'src/main/java/one/A.java': 'package example.shared;\nclass A {}',
      'src/main/java/two/B.java': 'package example.shared;\nclass B {}',
      'src/test/java/one/ATest.java': 'package example.test.shared;\nclass ATest {}',
      'src/test/java/two/BTest.java': 'package example.test.shared;\nclass BTest {}',
    },
    second: {
      'src/main/java/other/C.java': 'package example.shared;\nclass C {}',
      'src/test/java/other/CTest.java': 'package example.test.shared;\nclass CTest {}',
    },
  }), [
    '- Java main package shared across Maven modules: example.shared (first, second)',
    '- Java test package shared across Maven modules: example.test.shared (first, second)',
  ])
})

test('shared platform plugin packages are rejected without a P1 exception', () => {
  const files = Object.fromEntries(['', '.credential', '.resource'].map((suffix, index) => [
    `src/main/java/P${index}.java`,
    `package fun.fengwk.kkstudio.platform.plugin${suffix};\nclass P${index} {}`,
  ]))
  assert.deepEqual(diagnostics({
    'platform/plugin-api': files,
    'platform/runtime': files,
  }), [
    '- Java main package shared across Maven modules: fun.fengwk.kkstudio.platform.plugin (platform/plugin-api, platform/runtime)',
    '- Java main package shared across Maven modules: fun.fengwk.kkstudio.platform.plugin.credential (platform/plugin-api, platform/runtime)',
    '- Java main package shared across Maven modules: fun.fengwk.kkstudio.platform.plugin.resource (platform/plugin-api, platform/runtime)',
  ])
  assert.deepEqual(diagnostics({
    first: { 'src/main/java/A.java': 'package fun.fengwk.kkstudio.platform.plugin.other;' },
    second: { 'src/main/java/B.java': 'package fun.fengwk.kkstudio.platform.plugin.other;' },
  }), [
    '- Java main package shared across Maven modules: fun.fengwk.kkstudio.platform.plugin.other (first, second)',
  ])
})
