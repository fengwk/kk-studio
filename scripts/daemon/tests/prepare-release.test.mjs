/**
 * Contract tests for `scripts/daemon/prepare-release.sh`.
 *
 * The fixtures shim curl/npm while retaining real tag/version checks, SHA256 verification, archive
 * parsing and staging. They verify selection arguments and archive layout, not native ABI
 * compatibility; the host-matched CI smoke tests cover runnable platform binaries.
 */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import {
  DAEMON_VERSION,
  PLATFORMS,
  RELEASE_TAG,
  ReleaseFixture,
  sha256Of,
  withTempDir,
} from './support/release-fixture.mjs'
import { tarNames, writeTarGz } from './support/tar.mjs'

const readJson = (file) => JSON.parse(readFileSync(file, 'utf8'))

/** Run `body(fixture, tmp)` with a fresh fake repository. */
const withFixture = (body) => withTempDir((tmp) => body(new ReleaseFixture(tmp), tmp))
const archiveOf = (fixture, osName, arch) =>
  path.join(fixture.releaseDir(`${osName}-${arch}`), `kk-studio-daemon-${RELEASE_TAG}-${osName}-${arch}.tar.gz`)
const editManifest = (fixture, edit) => {
  const file = path.join(fixture.repo, 'daemon', 'runtime-manifest.json')
  const manifest = readJson(file)
  edit(manifest)
  writeFileSync(file, JSON.stringify(manifest))
}

describe('prepare-release.sh', () => {
  it('stages exactly five assets for linux', () =>
    withFixture((fixture) => {
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'])
      assert.equal(result.status, 0, result.stderr)
      const release = fixture.releaseDir('linux-amd64')
      const prefix = `kk-studio-daemon-${RELEASE_TAG}-linux-amd64`
      assert.deepEqual(
        readdirSync(release).sort(),
        [`${prefix}.json`, `${prefix}.tar.gz`, `${prefix}.tar.gz.sha256`, 'LICENSE', 'THIRD_PARTY_NOTICES'].sort(),
      )
      const archive = path.join(release, `${prefix}.tar.gz`)
      assert.equal(readFileSync(path.join(release, `${prefix}.tar.gz.sha256`), 'utf8').split(/\s+/)[0], sha256Of(archive))
      const metadata = readJson(path.join(release, `${prefix}.json`))
      assert.equal(metadata.tag, RELEASE_TAG)
      assert.equal(metadata.projectVersion, DAEMON_VERSION)
      assert.equal(metadata.os, 'linux')
      assert.equal(metadata.arch, 'amd64')
      assert.equal(metadata.artifactSha256, sha256Of(archive))
      assert.equal(metadata.entrypoint, 'main.js')
    }))

  it('rejects an asset version that differs from the manifest', () =>
    withFixture((fixture) => {
      // 运行时版本来自清单；URL 版本不同必须在下载和安装依赖前拒绝。
      editManifest(fixture, (manifest) => {
        manifest.codeServerVersion = '9.9.9-kk.2'
      })
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('not the frozen fork release asset'))
      assert.ok(!existsSync(fixture.releaseDir('linux-amd64')))
    }))

  it('packs the platform prebuilt, runtime and extension into the archive', () =>
    withFixture((fixture) => {
      assert.equal(fixture.run([RELEASE_TAG, 'linux', 'amd64']).status, 0)
      const names = tarNames(archiveOf(fixture, 'linux', 'amd64'))
      for (const expected of [
        'main.js',
        'package.json',
        'runtime-manifest.json',
        'node_modules/@koromix/koffi-linux-x64/index.js',
        'runtime/code-server/lib/node',
        'runtime/code-server/out/node/entry.js',
        'ide-extension/extension.js',
        'LICENSE',
        'THIRD_PARTY_NOTICES',
      ]) {
        assert.ok(names.includes(expected), expected)
      }
      assert.ok(!names.includes('node_modules/@koromix/koffi-darwin-x64'))
    }))

  it('selects the target optional prebuilt per platform', () => {
    const expected = [
      ['linux', 'amd64', 'koffi-linux-x64'],
      ['linux', 'arm64', 'koffi-linux-arm64'],
      ['macos', 'amd64', 'koffi-darwin-x64'],
      ['macos', 'arm64', 'koffi-darwin-arm64'],
      ['windows', 'amd64', 'koffi-win32-x64'],
    ]
    for (const [osName, arch, koffi] of expected) {
      withFixture((fixture) => {
        const result = fixture.run([RELEASE_TAG, osName, arch])
        assert.equal(result.status, 0, `${osName}-${arch}: ${result.stderr}`)
        const names = tarNames(archiveOf(fixture, osName, arch))
        assert.ok(names.includes(`node_modules/@koromix/${koffi}/index.js`), koffi)
        assert.ok(names.includes('runtime/code-server/out/node/entry.js'))
        assert.ok(names.includes(`runtime/code-server/lib/${osName === 'windows' ? 'node.exe' : 'node'}`))
        assert.ok(!names.some((name) => name.includes('koffi-freebsd')))
      })
    }
  })

  it('installs each platform lockfile without running lifecycle scripts', () => {
    // 跨平台暂存只安装锁定清单里的预编译成品：每个平台都必须按目标 --os/--cpu 选择 optional
    // 包并忽略生命周期脚本，否则异架构 install script 会在宿主上失败（旧实现即由此回归捕获）。
    const expected = [
      ['linux', 'amd64', 'linux', 'x64'],
      ['linux', 'arm64', 'linux', 'arm64'],
      ['macos', 'amd64', 'darwin', 'x64'],
      ['macos', 'arm64', 'darwin', 'arm64'],
      ['windows', 'amd64', 'win32', 'x64'],
    ]
    for (const [osName, arch, npmOs, npmCpu] of expected) {
      withFixture((fixture) => {
        const result = fixture.run([RELEASE_TAG, osName, arch])
        assert.equal(result.status, 0, `${osName}-${arch}: ${result.stderr}`)
        const invocations = readFileSync(fixture.npmLog, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line))
        assert.equal(invocations.length, 1)
        const [args] = invocations
        for (const expectedArg of ['ci', '--prefix', '--omit=dev', '--ignore-scripts', `--os=${npmOs}`, `--cpu=${npmCpu}`]) {
          assert.ok(args.includes(expectedArg), `${osName}-${arch}: ${expectedArg}`)
        }
      })
    }
  })

  it('the fake npm itself rejects installs that would run lifecycle scripts', () =>
    withFixture((fixture, tmp) => {
      // fixture 自身必须真的拒绝缺失 --ignore-scripts 的安装，否则跨平台回归无法捕获旧实现。
      const prefix = path.join(tmp, 'deps')
      mkdirSync(prefix)
      const result = spawnSync(path.join(fixture.bindir, 'npm'), ['--prefix', prefix, 'ci', '--omit=dev', '--os=linux', '--cpu=arm64'], {
        encoding: 'utf8',
      })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('--ignore-scripts'))
      assert.ok(!existsSync(path.join(prefix, 'node_modules')))
    }))

  it('prunes foreign koffi prebuilts', () =>
    withFixture((fixture) => {
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'], { FAKE_NPM_FOREIGN_PREBUILT: '1' })
      assert.equal(result.status, 0, result.stderr)
      const names = tarNames(archiveOf(fixture, 'linux', 'amd64'))
      assert.ok(names.includes('node_modules/@koromix/koffi-linux-x64/index.js'))
      assert.ok(!names.some((name) => name.includes('koffi-freebsd')))
    }))

  it('refuses to package before the runtime sha is frozen', () =>
    withFixture((fixture) => {
      editManifest(fixture, (manifest) => {
        delete manifest.codeServer['linux-amd64'].sha256
      })
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('not frozen yet'))
      assert.ok(!existsSync(fixture.releaseDir('linux-amd64')))
    }))

  it('rejects a missing target prebuilt', () =>
    withFixture((fixture) => {
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'], { FAKE_NPM_OMIT_PREBUILT: '1' })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('prebuilt'))
      assert.ok(!existsSync(fixture.releaseDir('linux-amd64')))
    }))

  it('rejects windows arm64', () =>
    withFixture((fixture) => {
      const result = fixture.run([RELEASE_TAG, 'windows', 'arm64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('windows supports amd64 only'))
    }))

  it('rejects a tag that differs from the daemon version', () =>
    withFixture((fixture) => {
      const result = fixture.run(['v9.9.9', 'linux', 'amd64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('must equal the daemon version'))
    }))

  it('rejects an unsafe tag or platform', () =>
    withFixture((fixture) => {
      assert.notEqual(fixture.run(['1.0.14', 'linux', 'amd64']).status, 0)
      assert.notEqual(fixture.run([RELEASE_TAG, 'plan9', 'amd64']).status, 0)
      assert.notEqual(fixture.run([RELEASE_TAG, 'linux', 'sparc']).status, 0)
    }))

  it('rejects a code-server link that escapes the archive', () =>
    withFixture((fixture, tmp) => {
      const platform = 'linux-amd64'
      const runtime = path.join(fixture.httpDir, `code-server-4.141.0-kk.1-${platform}.tar.gz`)
      const rootName = `code-server-4.141.0-kk.1-${platform}`
      rmSync(runtime)
      writeTarGz(runtime, [
        { name: rootName, type: 'dir' },
        { name: `${rootName}/lib`, type: 'dir' },
        { name: `${rootName}/lib/node`, type: 'file', content: '#!/bin/sh\n', mode: 0o755 },
        { name: `${rootName}/lib/escape`, type: 'symlink', linkname: '../../outside' },
        { name: `${rootName}/out`, type: 'dir' },
        { name: `${rootName}/out/node`, type: 'dir' },
        { name: `${rootName}/out/node/entry.js`, type: 'file', content: '// entry\n' },
      ])
      editManifest(fixture, (manifest) => {
        manifest.codeServer[platform].sha256 = sha256Of(runtime)
      })
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('escapes the extraction root'), result.stderr)
      assert.ok(!existsSync(fixture.releaseDir('linux-amd64')))
    }))

  it('rejects a code-server hash mismatch', () =>
    withFixture((fixture) => {
      editManifest(fixture, (manifest) => {
        manifest.codeServer['linux-amd64'].sha256 = '0'.repeat(64)
      })
      const result = fixture.run([RELEASE_TAG, 'linux', 'amd64'])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('SHA256'))
      assert.ok(!existsSync(fixture.releaseDir('linux-amd64')))
    }))

  it('prints help and rejects a bare invocation', () =>
    withFixture((fixture) => {
      assert.equal(fixture.run(['--help']).status, 0)
      assert.equal(fixture.run([]).status, 2)
    }))

  it('keeps the platform matrix at five platforms', () => {
    assert.equal(PLATFORMS.length, 5)
  })
})
