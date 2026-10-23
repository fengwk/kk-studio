/**
 * install.sh safety contracts: inputs mutated during the download, failed reinstall, unsafe managed
 * paths, foreign service definitions, reinstall data retention and idempotent uninstall.
 */

import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'
import { spawnSync } from 'node:child_process'

import { InstallFixture, RELEASE_TAG, sha256Of, withTempDir, writeExecutable } from './support/release-fixture.mjs'

const mode = (file) => statSync(file).mode & 0o777
const bytes = (file) => readFileSync(file)
const unitPath = (fixture) => path.join(fixture.home, '.config/systemd/user/kk-studio-daemon.service')

function withInstall(body) {
  return withTempDir((tmp) => {
    const fixture = new InstallFixture(tmp)
    const context = { tmp, fixture }
    ;[context.configFile, context.tokenFile] = fixture.makeInputs()
    context.install = (extraEnv) =>
      fixture.run(['install', '--config-file', context.configFile, '--token-file', context.tokenFile], { extraEnv })
    return body(context)
  })
}

describe('install.sh safety', () => {
  it('rejects inputs mutated during the download before the snapshot', () =>
    withInstall((context) => {
      // 下载窗口内被换掉/放宽的输入必须在发布前重新校验，且不得影响正在运行的安装。
      const { fixture, install } = context
      assert.equal(install().status, 0)
      const root = fixture.installRoot
      const before = Object.fromEntries(['daemon.json', 'daemon.token'].map((name) => [name, bytes(path.join(root, name))]))
      const program = bytes(path.join(root, 'lib/main.js'))
      const real = path.join(fixture.bindir, 'curl.real')
      renameSync(path.join(fixture.bindir, 'curl'), real)
      for (const [label, mutation, expected] of [
        ['symlink', () => `rm -f "${context.configFile}"; ln -s "${context.tokenFile}" "${context.configFile}"`, 'symbolic link'],
        ['public', () => `chmod 0644 "${context.configFile}"`, 'unsafe permissions'],
      ]) {
        for (const file of [context.configFile, context.tokenFile]) {
          rmSync(file, { force: true })
        }
        ;[context.configFile, context.tokenFile] = fixture.makeInputs()
        writeExecutable(
          path.join(fixture.bindir, 'curl'),
          '#!/bin/sh\n' +
            `"${real}" "$@"\n` +
            'status=$?\n' +
            `if [ "$status" -eq 0 ]; then case "$*" in *.tar.gz*) ${mutation()} ;; esac; fi\n` +
            'exit "$status"\n',
        )
        const result = install()
        assert.notEqual(result.status, 0, `${label}: ${result.stdout}`)
        assert.ok(result.stderr.includes(expected), label)
        assert.ok(!result.stderr.includes('token-value'), label)
        assert.ok(program.equals(bytes(path.join(root, 'lib/main.js'))), label)
        for (const [name, content] of Object.entries(before)) {
          assert.ok(content.equals(bytes(path.join(root, name))), `${label}: ${name}`)
        }
        assert.ok(existsSync(path.join(fixture.state, 'active')), 'the running service must be untouched')
        assert.ok(!existsSync(path.join(root, 'backups')), 'no backup may precede the input revalidation')
      }
    }))

  it('a failed reinstall keeps the running installation and backs nothing up', () =>
    withInstall(({ fixture, install }) => {
      // 预检失败必须发生在备份与停止之前，正在运行的服务和现有文件保持原样。
      assert.equal(install().status, 0)
      const root = fixture.installRoot
      const files = [path.join(root, 'daemon.json'), path.join(root, 'daemon.token'), path.join(root, 'lib/main.js'), unitPath(fixture)]
      const before = files.map((file) => bytes(file))
      const result = install({ FAKE_NODE_VERSION: '9.9.9' })
      assert.notEqual(result.status, 0)
      files.forEach((file, index) => assert.ok(before[index].equals(bytes(file)), file))
      assert.ok(existsSync(path.join(fixture.state, 'active')), 'the running service must not be stopped')
      assert.ok(!existsSync(path.join(root, 'backups')), 'no backup may precede the preflight')
    }))

  it('rejects unsafe managed paths without loosening permissions', () =>
    withInstall(({ fixture, install }) => {
      // 受管目录被放宽权限后必须拒绝重装，且不得自行修复或替换目录内容。
      assert.equal(install().status, 0)
      const root = fixture.installRoot
      const program = bytes(path.join(root, 'lib/main.js'))
      chmodSync(root, 0o770)
      const result = install()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('unsafe permissions'))
      assert.equal(mode(root), 0o770)
      assert.ok(program.equals(bytes(path.join(root, 'lib/main.js'))))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('rejects a foreign service definition without touching it', () =>
    withInstall(({ fixture, install }) => {
      // 归属其他用户的 service 定义必须 fail closed，不能覆盖、改动或绕过所有权标记。
      const unit = unitPath(fixture)
      mkdirSync(path.dirname(unit), { recursive: true })
      writeFileSync(unit, '# Managed by scripts/daemon/install.sh\n[Service]\nExecStart=/bin/true\n')
      writeFileSync(path.join(fixture.state, 'unit.path'), `${unit}\n`)
      const nativeStat = spawnSync('which', ['stat'], { encoding: 'utf8' }).stdout.trim()
      assert.ok(nativeStat)
      writeExecutable(
        path.join(fixture.bindir, 'stat'),
        '#!/bin/sh\n' +
          `if { [ "$1" = -c ] || [ "$1" = -f ]; } && [ "$2" = %u ] && [ "$3" = '${unit}' ]; then\n` +
          '  printf "%s\\n" "$(( $(id -u) + 1 ))"\n' +
          'else\n' +
          `  exec ${nativeStat} "$@"\n` +
          'fi\n',
      )
      const before = bytes(unit)
      const result = install()
      assert.notEqual(result.status, 0)
      for (const expected of ['belongs to another user', unit, '不要伪造所有权标记']) {
        assert.ok(result.stderr.includes(expected), expected)
      }
      assert.ok(before.equals(bytes(unit)))
      assert.ok(!readFileSync(path.join(fixture.state, 'commands'), 'utf8').includes('reset-failed'))
      assert.ok(!existsSync(path.join(fixture.installRoot, 'lib')))
    }))

  it('a reinstall refreshes program and config and keeps runtime data', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const root = fixture.installRoot
      const previous = bytes(path.join(root, 'lib/main.js'))
      // 守护进程自己创建的运行时数据目录（安装器不负责创建），重装必须原样保留。
      mkdirSync(path.join(root, 'logs'), { recursive: true, mode: 0o700 })
      writeFileSync(path.join(root, 'logs/daemon.log'), 'runtime log line\n')
      const [configFile, tokenFile] = fixture.makeInputs({
        config: '{"studioUrl":"https://other.example.com"}',
        token: 'rotated-token',
      })
      const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile])
      assert.equal(result.status, 0, result.stderr)
      assert.equal(readFileSync(path.join(root, 'daemon.json'), 'utf8'), '{"studioUrl":"https://other.example.com"}')
      assert.equal(readFileSync(path.join(root, 'daemon.token'), 'utf8'), 'rotated-token')
      assert.equal(readFileSync(path.join(root, 'logs/daemon.log'), 'utf8'), 'runtime log line\n')
      const backupsRoot = path.join(root, 'backups')
      const backups = readdirSync(backupsRoot).filter((name) => statSync(path.join(backupsRoot, name)).isDirectory())
      assert.equal(backups.length, 1)
      assert.ok(previous.equals(bytes(path.join(backupsRoot, backups[0], 'lib/main.js'))))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('uninstall is idempotent and preserves config, token, data and backups', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const root = fixture.installRoot
      const kept = new Map()
      const visit = (directory) => {
        for (const name of readdirSync(directory).sort()) {
          const file = path.join(directory, name)
          if (statSync(file).isDirectory()) {
            visit(file)
          } else if (!path.relative(root, file).split(path.sep).includes('lib')) {
            kept.set(file, bytes(file))
          }
        }
      }
      visit(root)
      assert.ok(kept.size > 0)
      for (let attempt = 0; attempt < 2; attempt += 1) {
        const result = fixture.run(['uninstall'])
        assert.equal(result.status, 0, result.stderr)
        assert.ok(!existsSync(path.join(root, 'lib')))
        assert.ok(!existsSync(unitPath(fixture)))
        for (const [file, content] of kept) {
          assert.ok(content.equals(bytes(file)), file)
        }
      }
    }))
})

describe('install.sh platform checks', () => {
  it('rejects an unknown architecture', () =>
    withTempDir((tmp) => {
      const fixture = new InstallFixture(tmp, { machine: 'riscv64' })
      const [configFile, tokenFile] = fixture.makeInputs()
      const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile])
      assert.notEqual(result.status, 0)
      assert.ok(!existsSync(path.join(fixture.installRoot, 'lib')))
    }))

  it('rejects an archive built for another platform', () =>
    withTempDir((tmp) => {
      // 归档名可以是本机平台，清单里的平台键不是时必须在写入前拒绝。
      const fixture = new InstallFixture(tmp)
      const foreign = path.join(fixture.root, 'foreign.tar.gz')
      fixture.buildArchive(foreign, 'macos-arm64')
      const archive = path.join(fixture.httpDir, fixture.asset)
      writeFileSync(archive, readFileSync(foreign))
      writeFileSync(path.join(fixture.httpDir, `${fixture.asset}.sha256`), `${sha256Of(archive)}  ${fixture.asset}\n`)
      const [configFile, tokenFile] = fixture.makeInputs()
      const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile])
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('不是本机平台'))
      assert.ok(!existsSync(path.join(fixture.installRoot, 'lib')))
    }))
})

describe('install.sh on macOS', () => {
  it('installs a launch agent', () =>
    withTempDir((tmp) => {
      const fixture = new InstallFixture(tmp, { hostOs: 'Darwin', machine: 'arm64' })
      const [configFile, tokenFile] = fixture.makeInputs()
      const result = fixture.run(['install', '--config-file', configFile, '--token-file', tokenFile])
      assert.equal(result.status, 0, result.stderr)
      const plist = readFileSync(path.join(fixture.home, 'Library/LaunchAgents/fun.fengwk.kkstudio.environment-daemon.plist'), 'utf8')
      assert.ok(plist.includes('Managed by scripts/daemon/install.sh'))
      assert.ok(plist.includes('runtime/code-server/lib/node'))
      assert.ok(plist.includes('<key>ProgramArguments</key>'))
      assert.ok(!plist.toLowerCase().includes('java'))
      assert.ok(!plist.toLowerCase().includes('.jar'))
      assert.ok(statSync(path.join(fixture.installRoot, 'logs')).isDirectory())
      assert.equal(fixture.releaseArch, 'arm64')
      assert.equal(fixture.asset, `kk-studio-daemon-${RELEASE_TAG}-macos-arm64.tar.gz`)
    }))
})
