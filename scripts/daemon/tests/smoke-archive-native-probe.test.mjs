/**
 * Execute the smoke's real native-probe JS with a real Node and a real host native.
 *
 * The shim-based smoke-archive tests never run JavaScript, so they cannot prove the probe's
 * Windows-helper exclusion or its root-relative foreign check. Here the exact heredoc from
 * `smoke-archive.sh` is written to a temporary `.mjs` and run against a fixture code-server tree
 * whose only loadable native is the repository's installed koffi prebuilt.
 *
 * The sibling `smoke-archive.ps1` cannot execute on Linux/macOS runners: its structural tests
 * assert that the real Git Bash is captured as an absolute path before PATH is emptied and that a
 * missing shell fails instead of being faked; the probe test runs the smoke's real native-probe JS
 * against a stub `native` module that provides only the call shapes, to prove the captured path is
 * serialized into `daemon.json` verbatim.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import { withTempDir } from './support/release-fixture.mjs'
import {
  FOREIGN_MARKER,
  WINDOWS_PROBE_NATIVE_STUB,
  extractNativeProbe,
  extractWindowsNativeProbe,
  hostArch,
  hostNativeBytes,
  hostOs,
  isUnixHost,
  windowsSmoke,
} from './support/smoke-archive.mjs'

const skip = !isUnixHost() && 'smoke-archive.sh runs on Linux and macOS'
const HOST = `${hostOs()}-${hostArch()}`

describe('code-server native probe (real node, real host native)', { skip }, () => {
  /** Run `body(helpers)` with a fresh probe script and code-server fixture root. */
  function withProbe(body) {
    return withTempDir((root) => {
      const nativeBytes = hostNativeBytes()
      const probe = path.join(root, 'probe-code-server-native.mjs')
      writeFileSync(probe, extractNativeProbe())
      const codeServer = path.join(root, 'code-server')
      const writeNative = (relative, data = nativeBytes, base = codeServer) => {
        const target = path.join(base, relative)
        mkdirSync(path.dirname(target), { recursive: true })
        writeFileSync(target, data)
      }
      const writeWindowsHelpers = (base = codeServer) => {
        const vendor = path.join(base, 'lib/vscode/extensions/ms-vscode.js-debug/src')
        mkdirSync(vendor, { recursive: true })
        // Both universal js-debug Windows helpers, deliberately with empty contents: on a Unix host
        // they are never required, so their payload must not matter.
        for (const name of [
          'win32-app-container-tokens.win32-x64-msvc-VCQE7GJP.node',
          'win32-app-container-tokens.win32-arm64-msvc-4ZJZ3U55.node',
        ]) {
          writeFileSync(path.join(vendor, name), '')
        }
      }
      const runProbe = (base = codeServer) =>
        spawnSync(process.execPath, [probe, base, HOST], { encoding: 'utf8', timeout: 60_000 })
      return body({ root, writeNative, writeWindowsHelpers, runProbe })
    })
  }

  const PTY = 'lib/vscode/node_modules/node-pty/build/Release/pty.node'

  it('ignores both Windows helpers next to a host native', () =>
    withProbe(({ writeNative, writeWindowsHelpers, runProbe }) => {
      writeNative(PTY)
      writeWindowsHelpers()
      const result = runProbe()
      assert.equal(result.status, 0, result.stderr)
      // Only the host native is counted; the two excluded helpers are not loaded.
      assert.equal(result.stdout.trim(), 'code-server native modules ok 1')
    }))

  it('rejects a Windows stem outside the vendor path', () =>
    withProbe(({ writeNative, runProbe }) => {
      writeNative(PTY)
      // Same Windows stem as a helper, but not the exact vendored js-debug path: still foreign.
      writeNative('lib/vscode/win32-app-container-tokens.win32-x64-msvc-OTHER.node', Buffer.from([0x7f, 0x45, 0x4c, 0x46]))
      const result = runProbe()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('not for'))
    }))

  it('fails when only the excluded helpers exist', () =>
    withProbe(({ writeWindowsHelpers, runProbe }) => {
      writeWindowsHelpers()
      const result = runProbe()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('code-server native modules are missing'))
    }))

  it('fails when the host native cannot load', () =>
    withProbe(({ writeNative, runProbe }) => {
      writeNative(PTY, Buffer.from('not a native module'))
      assert.notEqual(runProbe().status, 0)
    }))

  it('does not false-fail on a foreign marker in the absolute path', () =>
    withProbe(({ root, writeNative, runProbe }) => {
      // The fixture root's absolute path contains a marker foreign to this host; the probe must
      // decide on the root-relative path, so a valid host native still passes.
      const base = path.join(root, `carrier-${FOREIGN_MARKER[HOST]}`, 'code-server')
      writeNative(PTY, undefined, base)
      const result = runProbe(base)
      assert.equal(result.status, 0, result.stderr)
      assert.equal(result.stdout.trim(), 'code-server native modules ok 1')
    }))
})

describe('smoke-archive.ps1 contract', () => {
  const smoke = windowsSmoke()

  it('captures the real Git Bash before emptying the PATH', () => {
    // 清空 PATH 之前用 Get-Command 解析真实 bash.exe，取 .Source 绝对路径并校验 leaf。
    const capture = smoke.indexOf('$bashCommand = Get-Command bash.exe -CommandType Application')
    const emptyPath = smoke.indexOf("$env:PATH = Join-Path $root 'empty-path'")
    assert.ok(capture >= 0 && capture < emptyPath, 'bash must be captured while the real PATH is intact')
    for (const expected of [
      '$bashExecutable = $bashCommand.Source',
      "(Split-Path -Leaf $bashExecutable) -ne 'bash.exe'",
      // 缺 Git Bash 必须显式失败；不接受伪 shell 或 PATH 回退。
      "Fail 'Git Bash is required for the archive smoke but bash.exe was not found on PATH'",
      'Test-Path -LiteralPath $bashExecutable -PathType Leaf',
    ]) {
      assert.ok(smoke.includes(expected), expected)
    }
  })

  it('carries the captured bash into the native probe config', () => {
    // 捕获的绝对路径必须真的经 native probe 写进 daemon.json，而不是只被赋值后忽略。
    assert.ok(smoke.includes('& $node $probe $root $bashExecutable'))
    const probe = extractWindowsNativeProbe()
    assert.ok(probe.includes('const [root, bashExecutable] = process.argv.slice(2)'))
    assert.ok(probe.includes('bashExecutable })'))
  })

  it('still runs the frozen checks under the empty PATH', () => {
    // 收紧：--check-config 必须在空 PATH 下运行，通过显式 bashExecutable 通过，而不是把真实 PATH 还回来。
    const emptyPath = smoke.indexOf("$env:PATH = Join-Path $root 'empty-path'")
    const validCheck = smoke.indexOf('& $node $main --check-config $config\n')
    const restore = smoke.indexOf('$env:PATH = $previousPath')
    assert.ok(emptyPath >= 0 && emptyPath < validCheck, 'the frozen checks must run after PATH is emptied')
    assert.ok(validCheck < restore, 'the real PATH must only be restored in the finally block')
    for (const marker of ['--version', '--help', '--check-config']) {
      assert.ok(smoke.includes(marker), marker)
    }
    // 无效配置用例必须保留，不得放宽。
    for (const document of ['{"studioUrl":"not-a-url"}', '{"daemon":{"studioUrl":"http://127.0.0.1:59999"}}']) {
      assert.ok(smoke.includes(document), document)
    }
  })
})

describe('smoke-archive.ps1 native probe with a stub native module', { skip: !isUnixHost() && 'this probe regression runs on Linux and macOS' }, () => {
  it('writes the captured bash path into the config verbatim', () =>
    withTempDir((root) => {
      // Windows Git Bash 路径含反斜杠，必须原样往返，不能被 probe 丢失或改写。
      mkdirSync(path.join(root, 'native'))
      writeFileSync(path.join(root, 'native/index.js'), WINDOWS_PROBE_NATIVE_STUB)
      const probe = path.join(root, 'native-probe.mjs')
      writeFileSync(probe, extractWindowsNativeProbe())
      for (const bash of ['/usr/bin/bash', 'C:\\Program Files\\Git\\usr\\bin\\bash.exe']) {
        const result = spawnSync(process.execPath, [probe, root, bash], { encoding: 'utf8', timeout: 60_000 })
        assert.equal(result.status, 0, result.stderr)
        assert.equal(result.stdout.trim(), 'native directory lock ok')
        const config = JSON.parse(readFileSync(path.join(root, 'config/daemon.json'), 'utf8'))
        assert.deepEqual(config, { studioUrl: 'http://127.0.0.1:59999', bashExecutable: bash })
      }
    }))
})
