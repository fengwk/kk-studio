// 契约测试：fixture 只能通过 scripts/daemon/tests/export-update-script.mjs 读取生产更新脚本。
//
// 回归对象是真实 CI 缺陷：Windows PowerShell 5.1 转发 native argv 时吞掉引号，内联的
// `-e 'import { pathToFileURL } ...'` 到 Node 只剩碎片；PS 5.1 还会用控制台代码页解码
// native stdout，把正文里的中文注释改写成乱码。因此导出必须走「路径进、UTF-8 文件出」的
// 单一入口，本测试逐字节证明它与模块导出完全相等，并证明未知导出名失败关闭。

import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'

const testsDir = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(testsDir, '..', '..', '..')
const exporter = path.join(testsDir, 'export-update-script.mjs')
const updateScripts = path.join(repositoryRoot, 'daemon', 'src', 'update-scripts.ts')

/** 真实生产模块本身是唯一事实源：同一进程内导入作为逐字节对照基准。 */
const updateScriptModule = await import(pathToFileURL(updateScripts).href)

function exportToFile(name) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-update-export-'))
  try {
    const output = path.join(directory, 'update-script.txt')
    const stdout = execFileSync(process.execPath, [exporter, updateScripts, name, output], {
      encoding: 'utf8',
    })
    // 正文只走文件：stdout 保持空白，PS 5.1 的代码页解码因此无从介入。
    assert.equal(stdout, '', `${name}: the exporter must not write the script to stdout`)
    return { bytes: readFileSync(output), mode: statSync(output).mode }
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

test('the exporter reproduces every known update script byte for byte', () => {
  for (const name of ['UNIX_UPDATE_SCRIPT', 'WINDOWS_UPDATE_SCRIPT']) {
    const expected = Buffer.from(updateScriptModule[name], 'utf8')
    assert.ok(
      expected.includes(Buffer.from('许可', 'utf8')),
      `${name}: the production export must carry its multi-byte comments`,
    )
    const { bytes } = exportToFile(name)
    assert.ok(
      bytes.equals(expected),
      `${name}: the exported file must equal the module export byte for byte ` +
        `(${bytes.length} vs ${expected.length} bytes)`,
    )
    assert.notDeepEqual(
      [...bytes.subarray(0, 3)],
      [0xef, 0xbb, 0xbf],
      `${name}: the exported file must stay BOM-free`,
    )
  }
})

test('the exporter fails closed on an export name outside the allowlist', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-update-export-'))
  try {
    const output = path.join(directory, 'update-script.txt')
    assert.throws(() =>
      execFileSync(process.execPath, [exporter, updateScripts, 'CONFIG', output], {
        encoding: 'utf8',
        stdio: 'pipe',
      }),
    )
    assert.throws(() => statSync(output), { code: 'ENOENT' })
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('every fixture imports the shipped script through the shared exporter', () => {
  const powershellFixture = readFileSync(
    path.join(testsDir, 'test_daemon_update_windows.ps1'),
    'utf8',
  )
  assert.match(powershellFixture, /export-update-script\.mjs/u)
  // 旧实现把 JavaScript 源码交给 native argv / stdout；任何一条回来都会重现 CI 缺陷。
  assert.doesNotMatch(powershellFixture, /--input-type/u)
  assert.doesNotMatch(powershellFixture, /pathToFileURL|node:url/u)

  const nodeFixture = readFileSync(path.join(testsDir, 'support/release-fixture.mjs'), 'utf8')
  assert.match(nodeFixture, /export-update-script\.mjs/u)
  assert.doesNotMatch(nodeFixture, /--input-type/u)
})
