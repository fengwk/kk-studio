/**
 * Contract tests for `scripts/daemon/install.sh` on Linux (systemd): per-card UUID layout, install
 * and update semantics, service ownership, status, uninstall and cleanup.
 *
 * The fixture shims curl/uname/service managers and runs a real host Node for the installer's
 * JSON envelope/manifest checks; the installer still performs its real ownership/permission
 * checks, real SHA256 verification, real daemon `--check-config` preflight, real kernel-lock
 * helper ordering (a faithful directory-marker stand-in) and real atomic program publication from
 * its own `.work` staging directory. No real service is registered.
 */

import assert from 'node:assert/strict'
import { accessSync, chmodSync, constants, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'
import { spawnSync } from 'node:child_process'

import {
  ENVIRONMENT_ID,
  ENVIRONMENT_VERSION,
  InstallFixture,
  SECOND_ENVIRONMENT_ID,
  daemonJson,
  sha256Of,
  withTempDir,
  writeExecutable,
} from './support/release-fixture.mjs'
import { writeTarGz } from './support/tar.mjs'

const mode = (file) => statSync(file).mode & 0o777
const text = (file) => readFileSync(file, 'utf8')
const bytes = (file) => readFileSync(file)
const envRoot = (fixture) => fixture.envRoot
const unitPath = (fixture) => fixture.unitPath
const lastOperation = (fixture) => JSON.parse(text(path.join(envRoot(fixture), 'last-operation.json')))

/** Run `body(context)` with a fresh Linux install fixture, its private inputs and CLI shortcuts. */
function withInstall(body) {
  return withTempDir((tmp) => {
    const fixture = new InstallFixture(tmp)
    fixture.environmentId = ENVIRONMENT_ID
    const [configFile, tokenFile] = fixture.makeInputs()
    const context = {
      tmp,
      fixture,
      configFile,
      tokenFile,
      install: (extraEnv = {}, overrides = {}) =>
        fixture.run([
          'install',
          '--environment-id', overrides.environmentId ?? ENVIRONMENT_ID,
          '--version', overrides.version ?? '1.1.1',
          '--config-file', overrides.configFile ?? context.configFile,
          '--token-file', overrides.tokenFile ?? context.tokenFile,
        ], { extraEnv }),
      update: (extraEnv = {}, overrides = {}) =>
        fixture.run([
          'update',
          '--environment-id', overrides.environmentId ?? ENVIRONMENT_ID,
          '--version', overrides.version ?? '1.2.0',
        ], { extraEnv }),
      status: (environmentId = ENVIRONMENT_ID) => fixture.run(['status', '--environment-id', environmentId]),
      uninstall: (environmentId = ENVIRONMENT_ID) => fixture.run(['uninstall', '--environment-id', environmentId]),
    }
    return body(context)
  })
}

/** Publish the bytes of `fixture.asset` under another release version (same program content). */
function addRelease(fixture, version) {
  const asset = `kk-studio-daemon-v${version}-${fixture.releaseOs}-${fixture.releaseArch}.tar.gz`
  const archive = path.join(fixture.httpDir, asset)
  copyFileSync(path.join(fixture.httpDir, fixture.asset), archive)
  writeFileSync(`${archive}.sha256`, `${sha256Of(archive)}  ${asset}\n`)
  return asset
}

describe('install.sh on Linux', () => {
  it('installs one card into its UUID layout without backups and cleans its staging area', () =>
    withInstall(({ fixture, install }) => {
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      assert.ok(result.stdout.includes('READY'))
      const root = envRoot(fixture)
      assert.ok(existsSync(path.join(root, 'lib/main.js')))
      accessSync(path.join(root, 'lib/runtime/code-server/lib/node'), constants.X_OK)
      for (const name of ['daemon.json', 'daemon.token']) {
        assert.equal(mode(path.join(root, name)), 0o600, name)
      }
      // 同卡片只有一套受管服务；根目录不再有全局 daemon.json/token、backups 或 .work 残留。
      assert.ok(!existsSync(path.join(fixture.installRoot, 'daemon.json')))
      assert.ok(!existsSync(path.join(fixture.installRoot, 'daemon.token')))
      assert.ok(!existsSync(path.join(fixture.installRoot, 'backups')))
      assert.ok(!existsSync(path.join(root, '.work')))
      const unit = text(unitPath(fixture))
      assert.equal(unit.split('\n')[0], '# Managed by scripts/daemon/install.sh')
      assert.ok(unit.includes(`${root}/lib/runtime/code-server/lib/node`))
      assert.ok(unit.includes(`${root}/lib/main.js`))
      assert.ok(unit.includes(`${root}/daemon.json`))
      assert.ok(unit.includes(ENVIRONMENT_ID))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
      const record = lastOperation(fixture)
      assert.equal(record.type, 'install')
      assert.equal(record.targetVersion, '1.1.1')
      assert.equal(record.phase, 'done')
      assert.equal(record.result, 'succeeded')
      assert.equal(record.errorCode, null)
      assert.ok(text(path.join(root, 'last-operation.json')).length <= 4096)
      assert.doesNotMatch(result.stdout + result.stderr + unit, /token-value/)
    }))

  it('keeps two cards of the same user independent', () =>
    withInstall(({ fixture, install, uninstall, status }) => {
      fixture.environmentId = ENVIRONMENT_ID
      assert.equal(install().status, 0)
      const [secondConfig, secondToken] = fixture.makeInputs({ environmentId: SECOND_ENVIRONMENT_ID })
      fixture.environmentId = SECOND_ENVIRONMENT_ID
      const second = fixture.run([
        'install', '--environment-id', SECOND_ENVIRONMENT_ID, '--version', '1.1.1',
        '--config-file', secondConfig, '--token-file', secondToken,
      ])
      assert.equal(second.status, 0, second.stderr)
      const firstRoot = path.join(fixture.installRoot, 'environments', ENVIRONMENT_ID)
      const secondRoot = path.join(fixture.installRoot, 'environments', SECOND_ENVIRONMENT_ID)
      assert.ok(existsSync(path.join(firstRoot, 'lib/main.js')))
      assert.ok(existsSync(path.join(secondRoot, 'lib/main.js')))
      assert.notEqual(firstRoot, secondRoot)
      const firstUnit = path.join(fixture.home, `.config/systemd/user/kk-studio-daemon-${ENVIRONMENT_ID}.service`)
      const secondUnit = path.join(fixture.home, `.config/systemd/user/kk-studio-daemon-${SECOND_ENVIRONMENT_ID}.service`)
      assert.ok(existsSync(firstUnit) && existsSync(secondUnit))
      assert.ok(text(firstUnit).includes(firstRoot))
      assert.ok(text(secondUnit).includes(secondRoot))

      // 卸载一张卡片只移除该卡片的服务与程序，另一张卡片的服务定义、程序和配置不受影响。
      writeFileSync(path.join(fixture.state, 'commands'), '')
      assert.equal(uninstall(ENVIRONMENT_ID).status, 0)
      assert.ok(!existsSync(firstUnit))
      assert.ok(!existsSync(path.join(firstRoot, 'lib')))
      assert.ok(existsSync(path.join(firstRoot, 'daemon.json')))
      assert.ok(existsSync(secondUnit))
      assert.ok(existsSync(path.join(secondRoot, 'lib/main.js')))
      const uninstallCommands = text(path.join(fixture.state, 'commands'))
      assert.match(uninstallCommands, new RegExp(`--user stop kk-studio-daemon-${ENVIRONMENT_ID}.service`))
      assert.doesNotMatch(uninstallCommands, new RegExp(SECOND_ENVIRONMENT_ID))
    }))

  it('records the operation id supplied by an online update and ignores invalid values', () =>
    withInstall(({ fixture, install }) => {
      const supplied = '9f1c1c5e-2f4b-4b6a-8f2e-3c4d5e6f7a8b'
      assert.equal(install({ KK_STUDIO_UPDATE_OPERATION_ID: supplied }).status, 0)
      assert.equal(lastOperation(fixture).operationId, supplied)
      writeFileSync(path.join(fixture.state, 'commands'), '')
      assert.equal(install({ KK_STUDIO_UPDATE_OPERATION_ID: 'not-a-uuid' }).status, 0)
      assert.notEqual(lastOperation(fixture).operationId, 'not-a-uuid')
    }))

  it('update replaces only the program and service and never writes config or token', () =>
    withInstall(({ fixture, install, update }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const config = bytes(path.join(root, 'daemon.json'))
      const token = bytes(path.join(root, 'daemon.token'))
      const unit = text(unitPath(fixture))
      // A marker inside the program directory proves the directory was replaced, not merged.
      writeFileSync(path.join(root, 'lib/old-marker'), 'old')
      addRelease(fixture, '1.2.0')
      const result = update({ FAKE_NODE_VERSION: '1.2.0' })
      assert.equal(result.status, 0, result.stderr)
      assert.ok(result.stdout.includes('配置与凭据未改动'))
      assert.ok(!existsSync(path.join(root, 'lib/old-marker')))
      assert.ok(bytes(path.join(root, 'daemon.json')).equals(config))
      assert.ok(bytes(path.join(root, 'daemon.token')).equals(token))
      assert.equal(text(unitPath(fixture)), unit)
      assert.deepEqual(readdirSync(root).filter((name) => name.startsWith('.daemon')), [])
      assert.ok(!existsSync(path.join(root, '.work')))
      assert.equal(lastOperation(fixture).type, 'update')
      assert.equal(lastOperation(fixture).targetVersion, '1.2.0')
    }))

  it('update rejects configuration options before any network access', () =>
    withInstall(({ fixture, configFile, tokenFile }) => {
      const log = path.join(fixture.state, 'update-curl')
      for (const suffix of [
        ['--config-file', configFile], ['--token-file', tokenFile],
      ]) {
        const result = fixture.run([
          'update', '--environment-id', ENVIRONMENT_ID, '--version', '1.2.0', ...suffix,
        ], { extraEnv: { FAKE_CURL_LOG: log } })
        assert.notEqual(result.status, 0, JSON.stringify(suffix))
        assert.match(result.stderr, /unknown option/)
      }
      assert.equal(existsSync(log), false)
    }))

  it('update requires an existing managed installation', () =>
    withInstall(({ update }) => {
      const result = update()
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /未安装/)
    }))

  it('update re-validates the current configuration after the service stops', () =>
    withInstall(({ fixture, install, update }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      writeFileSync(path.join(root, 'lib/new-marker'), 'new')
      const config = daemonJson({ environmentId: ENVIRONMENT_ID, studioUrl: 'invalid' })
      writeFileSync(path.join(root, 'daemon.json'), config)
      addRelease(fixture, '1.2.0')
      const result = update({ FAKE_NODE_VERSION: '1.2.0' })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /\[6\/7 停止发布启动\]/)
      // 停机后复检失败：旧程序目录仍在，配置内容未变，结果记录 validate 阶段。
      assert.ok(existsSync(path.join(root, 'lib/new-marker')))
      assert.equal(text(path.join(root, 'daemon.json')), config)
      assert.equal(lastOperation(fixture).result, 'failed')
      assert.equal(lastOperation(fixture).phase, 'validate')
      assert.ok(existsSync(path.join(fixture.state, 'active')) === false)
    }))

  it('rejects the old flat configuration before downloading', () =>
    withInstall(({ fixture, configFile, install }) => {
      writeFileSync(configFile, '{"studioUrl":"https://studio.example.com"}')
      const log = path.join(fixture.state, 'old-config-curl')
      const result = install({ FAKE_CURL_LOG: log })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /配置格式不是当前版本/)
      assert.equal(existsSync(log), false)
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
    }))

  it('rejects another card configuration without touching the installation', () =>
    withInstall(({ fixture, install, configFile, tokenFile }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const before = bytes(path.join(root, 'daemon.json'))
      const [otherConfig, otherToken] = fixture.makeInputs({ environmentId: SECOND_ENVIRONMENT_ID })
      const result = install({}, { configFile: otherConfig, tokenFile: otherToken })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /配置属于另一张环境卡片/)
      assert.ok(bytes(path.join(root, 'daemon.json')).equals(before))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('rejects an invalid daemonConfig through the release preflight', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const before = bytes(path.join(root, 'daemon.json'))
      const config = JSON.stringify({
        environmentId: ENVIRONMENT_ID,
        version: ENVIRONMENT_VERSION,
        daemonConfig: { invalid: true, studioUrl: 'https://studio.example.com' },
      })
      const [configFile, tokenFile] = fixture.makeInputs({ config })
      const result = install({}, { configFile, tokenFile })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('Invalid daemon configuration'), result.stderr)
      assert.ok(bytes(path.join(root, 'daemon.json')).equals(before))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('cleans a previous .work residue only after taking the operation lock', () =>
    withInstall(({ fixture, install }) => {
      const root = envRoot(fixture)
      mkdirSync(path.join(root, '.work'), { recursive: true, mode: 0o700 })
      writeFileSync(path.join(root, '.work/residue'), 'stale')
      const result = install()
      assert.equal(result.status, 0, result.stderr)
      assert.ok(!existsSync(path.join(root, '.work')))
    }))

  it('reports a busy card and changes nothing', () =>
    withInstall(({ fixture, install }) => {
      const root = envRoot(fixture)
      mkdirSync(root, { recursive: true, mode: 0o700 })
      chmodSync(root, 0o700)
      // 原子目录标记模拟另一个进程持有 install.lock。
      mkdirSync(path.join(root, '.fake-install-lock'))
      const result = install()
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /另一个安装操作正在执行/)
      assert.ok(!existsSync(path.join(root, 'lib')))
      assert.ok(!existsSync(path.join(fixture.state, 'active')))
      assert.ok(!existsSync(path.join(root, 'last-operation.json')))
    }))

  it('fails without touching the program when the runtime lock is not released', () =>
    withInstall(({ fixture, install }) => {
      const result = install({ FAKE_DAEMON_LOCKED: '1' })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /\[6\/7 停止发布启动\].*运行锁/)
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
      assert.ok(!existsSync(path.join(fixture.state, 'active')))
    }))

  it('reports status absent, active and inactive', () =>
    withInstall(({ fixture, install, status, uninstall }) => {
      assert.equal(status().status, 1)
      assert.equal(install().status, 0)
      assert.equal(status().status, 0)
      unlinkSync(path.join(fixture.state, 'active'))
      assert.equal(status().status, 3)
      assert.equal(uninstall().status, 0)
      assert.equal(status().status, 1)
    }))

  it('uninstall removes only the owned service and program', () =>
    withInstall(({ fixture, install, uninstall }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      mkdirSync(path.join(root, 'skills/skill-a'), { recursive: true, mode: 0o700 })
      chmodSync(path.join(root, 'skills'), 0o700)
      writeFileSync(path.join(root, 'skills/skill-a/SKILL.md'), 'user data')
      const result = uninstall()
      assert.equal(result.status, 0, result.stderr)
      assert.ok(!existsSync(path.join(root, 'lib')))
      assert.ok(!existsSync(unitPath(fixture)))
      assert.ok(existsSync(path.join(root, 'daemon.json')))
      assert.ok(existsSync(path.join(root, 'daemon.token')))
      assert.ok(existsSync(path.join(root, 'skills/skill-a/SKILL.md')))
      assert.equal(lastOperation(fixture).type, 'uninstall')
      assert.equal(lastOperation(fixture).result, 'succeeded')
      // 再次卸载保持成功且不虚构错误。
      assert.equal(uninstall().status, 0)
    }))

  it('uninstall still removes a program left behind after the service definition disappeared', () =>
    withInstall(({ fixture, install, uninstall }) => {
      // 上一次卸载可能已移除服务定义但未能删除 lib；再次卸载必须完成清理而不是报告“无事可做”。
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      unlinkSync(unitPath(fixture))
      const result = uninstall()
      assert.equal(result.status, 0, result.stderr)
      assert.ok(!existsSync(path.join(root, 'lib')))
      assert.ok(existsSync(path.join(root, 'daemon.json')))
    }))

  it('uninstall refuses to delete a program whose helper copy is unavailable', () =>
    withInstall(({ fixture, install, uninstall }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      // 缺少 native helper 时无法取得管理副本：必须 fail closed，不能无锁删除。
      unlinkSync(path.join(root, 'lib/native/lock-helper.js'))
      const result = uninstall()
      assert.notEqual(result.status, 0)
      assert.ok(existsSync(unitPath(fixture)))
      assert.ok(existsSync(path.join(root, 'lib/main.js')))
    }))

  it('rejects duplicate, unknown and non-UUID options before network access', () =>
    withInstall(({ fixture, configFile, tokenFile, install, status, uninstall }) => {
      const log = path.join(fixture.state, 'invalid-curl')
      for (const args of [
        ['install', '--environment-id', ENVIRONMENT_ID, '--environment-id', ENVIRONMENT_ID, '--version', '1.1.1', '--config-file', configFile, '--token-file', tokenFile],
        ['install', '--environment-id', ENVIRONMENT_ID, '--version', '1.1.1', '--config-file', configFile, '--token-file', tokenFile, '--unknown-option', 'x'],
        ['install', '--environment-id', 'AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA', '--version', '1.1.1', '--config-file', configFile, '--token-file', tokenFile],
        ['install', '--environment-id', '../bad', '--version', '1.1.1', '--config-file', configFile, '--token-file', tokenFile],
        ['install', '--environment-id', ENVIRONMENT_ID, '--version', '../bad', '--config-file', configFile, '--token-file', tokenFile],
        ['install', '--environment-id', ENVIRONMENT_ID, '--version', '', '--config-file', configFile, '--token-file', tokenFile],
        ['update', '--environment-id', ENVIRONMENT_ID],
        ['update', '--environment-id', ENVIRONMENT_ID, '--version', 'x', '--version', 'y'],
        ['status', '--environment-id', ENVIRONMENT_ID, '--version', '1'],
        ['uninstall', '--environment-id', ENVIRONMENT_ID, '--config-file', configFile],
      ]) {
        const result = fixture.run(args, { extraEnv: { FAKE_CURL_LOG: log } })
        assert.notEqual(result.status, 0, JSON.stringify(args))
      }
      assert.equal(existsSync(log), false)
      assert.equal(status().status, 1)
      assert.equal(uninstall().status, 0)
    }))

  it('rejects a checksum mismatch and unsafe archive paths without publishing', () =>
    withInstall(({ tmp, fixture, install }) => {
      const archive = path.join(fixture.httpDir, fixture.asset)
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${'0'.repeat(64)}  ${fixture.asset}\n`)
      assert.notEqual(install().status, 0)
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))

      // 即使 SHA 已通过，也必须在解包前拒绝逃逸路径。
      fixture.buildArchive(archive)
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${sha256Of(archive)}  ${fixture.asset}\n`)
      const extracted = path.join(tmp, 'extract-called')
      const realTar = spawnSync('which', ['tar'], { encoding: 'utf8' }).stdout.trim()
      assert.ok(realTar)
      writeExecutable(path.join(fixture.bindir, 'tar'), `#!/bin/sh\nif [ "$1" = "-xzf" ]; then : > "${extracted}"; fi\nexec "${realTar}" "$@"\n`)
      const outside = path.join(tmp, 'absolute-escape')
      writeTarGz(archive, [{ name: '../escape', type: 'file', content: 'evil' }, { name: outside, type: 'file', content: 'evil' }])
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${sha256Of(archive)}  ${fixture.asset}\n`)
      const result = install()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('不安全路径'), result.stderr)
      assert.ok(!existsSync(extracted))
      assert.ok(!existsSync(outside))
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
    }))

  it('rejects a version mismatch and unsafe input permissions', () =>
    withInstall(({ fixture, configFile, install }) => {
      assert.notEqual(install({ FAKE_NODE_VERSION: '9.9.9' }).status, 0)
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
      chmodSync(configFile, 0o644)
      assert.notEqual(install().status, 0)
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
    }))

  it('uses the system bash and prints help', () =>
    withInstall(({ fixture, install }) => {
      writeExecutable(path.join(fixture.bindir, 'bash'), '#!/bin/sh\nexit 99\n')
      assert.equal(install().status, 0)
      assert.equal(fixture.run(['help']).status, 0)
      assert.notEqual(fixture.run(['unknown-command']).status, 0)
    }))
})
