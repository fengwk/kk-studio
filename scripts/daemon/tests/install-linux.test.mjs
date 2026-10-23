/**
 * Contract tests for `scripts/daemon/install.sh` on Linux (systemd): install, publication rollback,
 * option and archive validation, status and uninstall.
 *
 * The fixture shims curl/uname/service managers; the installer still performs its real
 * ownership/permission checks, real SHA256 verification, real release `--check-config` preflight
 * and real atomic program publication. No real service is registered.
 */

import assert from 'node:assert/strict'
import { accessSync, chmodSync, constants, existsSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'
import { spawnSync } from 'node:child_process'

import { InstallFixture, sha256Of, withTempDir, writeExecutable } from './support/release-fixture.mjs'
import { writeTarGz } from './support/tar.mjs'

const mode = (file) => statSync(file).mode & 0o777
const text = (file) => readFileSync(file, 'utf8')
const unitPath = (fixture) => path.join(fixture.home, '.config/systemd/user/kk-studio-daemon.service')

/** Run `body(context)` with a fresh Linux install fixture and its private inputs. */
function withInstall(body) {
  return withTempDir((tmp) => {
    const fixture = new InstallFixture(tmp)
    const [configFile, tokenFile] = fixture.makeInputs()
    const context = {
      tmp,
      fixture,
      configFile,
      tokenFile,
      install: (extraEnv) =>
        fixture.run(['install', '--config-file', context.configFile, '--token-file', context.tokenFile], { extraEnv }),
    }
    return body(context)
  })
}

const libDir = (fixture) => path.join(fixture.installRoot, 'lib')

describe('install.sh on Linux', () => {
  it('uses archive progress only when stderr is a TTY, even with redirected stdin', () =>
    withInstall(({ fixture, configFile, tokenFile }) => {
      const log = path.join(fixture.state, 'curl-options')
      const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile], {
        stderrTTY: true, extraEnv: { FAKE_CURL_LOG: log },
      })
      assert.equal(result.status, 0, result.stdout + result.stderr)
      const calls = text(log).trim().split('\n').map(line => JSON.parse(line))
      assert.deepEqual(calls.map(args => args.includes('--progress-bar')), [false, true, false])
      assert.ok(calls.every(args => args[0] === '-q' && args.includes('--show-error')))
    }))
  it('clears an old start limit after reload and before restart, retaining limits', () =>
    withInstall(({ fixture, install }) => {
      writeFileSync(path.join(fixture.state, 'start-limit'), '')
      assert.notEqual(spawnSync(path.join(fixture.bindir, 'systemctl'), ['--user', 'restart', 'kk-studio-daemon.service'], {
        env: { ...process.env, FAKE_SERVICE_STATE: fixture.state },
      }).status, 0)
      writeFileSync(path.join(fixture.state, 'commands'), '')
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      const commands = text(path.join(fixture.state, 'commands'))
      assert.match(commands, /daemon-reload\n--user enable.*\n--user reset-failed.*\n--user restart/)
      assert.match(text(unitPath(fixture)), /StartLimitIntervalSec=300\nStartLimitBurst=5/)
      assert.deepEqual([...result.stderr.matchAll(/\[(\d)\/7 /g)].map(m => Number(m[1])), [1, 2, 3, 4, 5, 6, 7])
      assert.doesNotMatch(result.stderr + result.stdout, /token-value|\x1b|\r/)
    }))

  it('hard fails reset without restarting and reports publication and backup', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      writeFileSync(path.join(fixture.state, 'commands'), '')
      const result = install({ FAKE_RESET_FAIL: '1' })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /安装失败 \[6\/7 发布启动\].*失败计数/)
      assert.match(result.stderr, /备份：.*backups\/install\./)
      assert.doesNotMatch(text(path.join(fixture.state, 'commands')), /restart/)
    }))

  it('translates only current startup diagnostics without exposing raw secrets', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      for (const current of ['', 'invalid registration token injected-secret']) {
        const result = install({ FAKE_START_FAIL: '1', FAKE_CURRENT_JOURNAL: current })
        assert.notEqual(result.status, 0)
        assert.match(result.stderr, /安装失败 \[6\/7 发布启动\]/)
        assert.equal(result.stderr.includes('在 Studio 重新生成安装命令'), Boolean(current))
        assert.doesNotMatch(result.stderr, /injected-secret|old-secret|token-value/)
        assert.match(result.stderr, /journalctl .*--since/)
      }
    }))

  it('reports archive download failure before any publication', () =>
    withInstall(({ fixture, install }) => {
      unlinkSync(path.join(fixture.httpDir, fixture.asset))
      const result = install()
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /安装失败 \[3\/7 下载归档\]/)
      assert.match(result.stderr, /现有程序和配置未改动/)
      assert.match(result.stderr, /备份：无/)
    }))

  it('installs the program, config, token and service', () =>
    withInstall(({ fixture, install }) => {
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      assert.ok(result.stdout.includes('READY'))
      const root = fixture.installRoot
      assert.ok(existsSync(path.join(root, 'lib/main.js')))
      accessSync(path.join(root, 'lib/runtime/code-server/lib/node'), constants.X_OK)
      for (const name of ['daemon.json', 'daemon.token']) {
        assert.equal(mode(path.join(root, name)), 0o600, name)
      }
      const unit = text(unitPath(fixture))
      assert.equal(unit.split('\n')[0], '# Managed by scripts/daemon/install.sh')
      assert.ok(unit.includes(`${root}/lib/runtime/code-server/lib/node`))
      assert.ok(unit.includes(`${root}/lib/main.js`))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('restores the previous program when publication fails', () =>
    withInstall(({ tmp, fixture, install }) => {
      // 新发行目录改名失败时，已经移进备份的旧 lib 必须回到受管位置。
      assert.equal(install().status, 0)
      const previous = text(path.join(libDir(fixture), 'main.js'))
      const script = text(fixture.script)
      const patched = script.replace('mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"', 'false')
      assert.notEqual(script, patched)
      fixture.script = path.join(tmp, 'install-fail-publish.sh')
      writeFileSync(fixture.script, patched)
      const result = install()
      assert.notEqual(result.status, 0)
      assert.equal(text(path.join(libDir(fixture), 'main.js')), previous)
      assert.ok(result.stderr.includes('已恢复上一版程序'))
    }))

  it('reports it when restoring the previous program also fails', () =>
    withInstall(({ tmp, fixture, install }) => {
      // 发布失败后再恢复失败时，必须明确报告，不能把旧程序仍在备份里说成已恢复。
      assert.equal(install().status, 0)
      const patched = text(fixture.script)
        .replace('mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"', 'false')
        .replace('mv "$BACKUP_DIR/lib" "$INSTALLED_LIB"', 'false')
      assert.ok(!patched.includes('mv "$DOWNLOAD_DIR/extract" "$INSTALLED_LIB"'))
      assert.ok(!patched.includes('mv "$BACKUP_DIR/lib" "$INSTALLED_LIB"'))
      fixture.script = path.join(tmp, 'install-fail-restore.sh')
      writeFileSync(fixture.script, patched)
      const result = install()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('未能恢复'))
      assert.ok(!existsSync(libDir(fixture)))
    }))

  it('backs up the previous program on a second install', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      const backupsRoot = path.join(fixture.installRoot, 'backups')
      const backups = readdirSync(backupsRoot).filter((name) => name.startsWith('install.'))
      assert.ok(backups.length > 0, 'a backup directory must be created on reinstall')
      const backup = path.join(backupsRoot, backups[0])
      assert.ok(statSync(path.join(backup, 'lib')).isDirectory())
      assert.equal(mode(path.join(backup, 'daemon.token')), 0o600)
    }))

  it('rejects unsafe input permissions', () =>
    withInstall(({ fixture, configFile, install }) => {
      chmodSync(configFile, 0o644)
      assert.notEqual(install().status, 0)
      assert.ok(!existsSync(libDir(fixture)))
    }))

  it('rejects a non-private input directory', () =>
    withInstall(({ tmp, fixture, install }) => {
      chmodSync(path.join(tmp, 'inputs'), 0o755)
      assert.notEqual(install().status, 0)
      assert.ok(!existsSync(libDir(fixture)))
    }))

  it('rejects duplicate and unknown options', () =>
    withInstall(({ fixture, configFile, tokenFile }) => {
      const duplicate = fixture.run(['install', '--config-file', configFile, '--config-file', configFile, '--token-file', tokenFile])
      assert.notEqual(duplicate.status, 0)
      const unknown = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile, '--java-home', '/opt/jdk'])
      assert.notEqual(unknown.status, 0)
    }))

  it('rejects a checksum mismatch', () =>
    withInstall(({ fixture, install }) => {
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${'0'.repeat(64)}  ${fixture.asset}\n`)
      assert.notEqual(install().status, 0)
      assert.ok(!existsSync(libDir(fixture)))
    }))

  it('rejects SHA-verified escaping paths before extraction', () =>
    withInstall(({ tmp, fixture, install }) => {
      // 即使 SHA 已通过，也必须在 tar 解包前拒绝逃逸路径。
      const extracted = path.join(tmp, 'extract-called')
      const realTar = spawnSync('which', ['tar'], { encoding: 'utf8' }).stdout.trim()
      assert.ok(realTar)
      writeExecutable(path.join(fixture.bindir, 'tar'), `#!/bin/sh\nif [ "$1" = "-xzf" ]; then : > "${extracted}"; fi\nexec "${realTar}" "$@"\n`)
      const outside = path.join(tmp, 'absolute-escape')
      const archive = path.join(fixture.httpDir, fixture.asset)
      for (const member of ['../escape', outside]) {
        writeTarGz(archive, [{ name: member, type: 'file', content: 'evil' }])
        writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${sha256Of(archive)}  ${fixture.asset}\n`)
        const result = install()
        assert.notEqual(result.status, 0, member)
        assert.ok(result.stderr.includes('不安全路径'), member)
        assert.ok(!existsSync(extracted), member)
        assert.ok(!existsSync(outside), member)
        assert.ok(!existsSync(libDir(fixture)), member)
      }
    }))

  it('rejects a non-official tag', () =>
    withInstall(({ install }) => {
      const result = install({ FAKE_TAG: 'main' })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('invalid official release tag'))
    }))

  it('rejects a version mismatch', () =>
    withInstall(({ fixture, install }) => {
      assert.notEqual(install({ FAKE_NODE_VERSION: '9.9.9' }).status, 0)
      assert.ok(!existsSync(libDir(fixture)))
    }))

  it('rejects an invalid config without changing the installation', () =>
    withInstall(({ fixture }) => {
      // 真实契约是裸 daemon 对象：既拒绝非法取值，也拒绝外层 "daemon" 包裹的假结构。
      for (const config of [
        '{"invalid":true,"studioUrl":"https://studio.example.com"}',
        '{"daemon":{"studioUrl":"https://studio.example.com"}}',
      ]) {
        const [configFile, tokenFile] = fixture.makeInputs({ config })
        const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile])
        assert.notEqual(result.status, 0, config)
        assert.ok(result.stderr.includes('Invalid daemon configuration'), config)
        assert.ok(!existsSync(libDir(fixture)), config)
      }
    }))

  it('reports status absent before install', () =>
    withInstall(({ fixture }) => {
      assert.equal(fixture.run(['status']).status, 1)
    }))

  it('distinguishes active, inactive and absent status', () =>
    withInstall(({ fixture, install }) => {
      // 0 = active, 3 = installed but inactive, 1 = not installed. This is the documented status
      // contract and must not collapse "installed but stopped" into "absent".
      assert.equal(install().status, 0)
      assert.equal(fixture.run(['status']).status, 0)
      unlinkSync(path.join(fixture.state, 'active'))
      assert.equal(fixture.run(['status']).status, 3)
      assert.equal(fixture.run(['uninstall']).status, 0)
      assert.equal(fixture.run(['status']).status, 1)
    }))

  it('never echoes the registration token', () =>
    withInstall(({ fixture, install }) => {
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${'0'.repeat(64)}  ${fixture.asset}\n`)
      const failed = install()
      assert.notEqual(failed.status, 0)
      for (const output of [result.stdout, result.stderr, failed.stdout, failed.stderr]) {
        assert.ok(!output.includes('token-value'))
      }
    }))

  it('uses the system bash, not another bash on PATH', () =>
    withInstall(({ fixture, install }) => {
      // The macOS gate runs under /bin/bash (3.2). A competing PATH bash must not replace it.
      writeExecutable(path.join(fixture.bindir, 'bash'), '#!/bin/sh\nexit 99\n')
      const result = install()
      assert.equal(result.status, 0, result.stderr)
    }))

  it('uninstall removes the program and keeps config', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const result = fixture.run(['uninstall'])
      assert.equal(result.status, 0, result.stderr)
      assert.ok(!existsSync(libDir(fixture)))
      assert.ok(!existsSync(unitPath(fixture)))
      assert.ok(existsSync(path.join(fixture.installRoot, 'daemon.json')))
      assert.ok(existsSync(path.join(fixture.installRoot, 'daemon.token')))
    }))

  it('prints help and rejects an unknown command', () =>
    withInstall(({ fixture }) => {
      assert.equal(fixture.run(['help']).status, 0)
      assert.notEqual(fixture.run(['upgrade']).status, 0)
    }))
})
