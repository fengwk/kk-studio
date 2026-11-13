/**
 * install.sh safety contracts for the per-card UUID layout: inputs mutated during the download,
 * foreign service definitions and program directories, unsafe managed permissions, failed
 * reinstalls, data retention and the bounded last-operation record.
 */

import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'
import { spawnSync } from 'node:child_process'

import {
  ENVIRONMENT_ID,
  InstallFixture,
  SECOND_ENVIRONMENT_ID,
  daemonJson,
  withTempDir,
  writeExecutable,
} from './support/release-fixture.mjs'

const mode = (file) => statSync(file).mode & 0o777
const bytes = (file) => readFileSync(file)
const unitPath = (fixture) => fixture.unitPath
const envRoot = (fixture) => fixture.envRoot
// Test-only host override lets Linux hosts exercise the Darwin fixture; CI uses the real host.
const HOST_OS = process.env.TEST_INSTALLER_HOST_OS ?? (process.platform === 'darwin' ? 'Darwin' : 'Linux')
const MACHINE = process.env.TEST_INSTALLER_MACHINE ?? (process.arch === 'arm64' ? 'arm64' : 'x86_64')
const managedMarker = (fixture) =>
  fixture.hostOs === 'Linux' ? '# Managed by scripts/daemon/install.sh' : '<!-- Managed by scripts/daemon/install.sh -->'

function withInstall(body) {
  return withTempDir((tmp) => {
    const fixture = new InstallFixture(tmp, { hostOs: HOST_OS, machine: MACHINE })
    fixture.environmentId = ENVIRONMENT_ID
    const [configFile, tokenFile] = fixture.makeInputs()
    const context = { tmp, fixture, configFile, tokenFile }
    context.install = (extraEnv = {}) => fixture.run([
      'install', '--environment-id', ENVIRONMENT_ID, '--version', '1.1.1',
      '--config-file', context.configFile, '--token-file', context.tokenFile,
    ], { extraEnv })
    context.update = (extraEnv = {}) => fixture.run([
      'update', '--environment-id', ENVIRONMENT_ID, '--version', '1.2.0',
    ], { extraEnv })
    return body(context)
  })
}

describe('install.sh safety', () => {
  it('rejects inputs mutated during the download before publication', () =>
    withInstall((context) => {
      const { fixture, install } = context
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const before = {
        config: bytes(path.join(root, 'daemon.json')),
        token: bytes(path.join(root, 'daemon.token')),
        program: bytes(path.join(root, 'lib/kk-studio-daemon')),
      }
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
        assert.ok(result.stderr.includes(expected), `${label}: ${result.stderr}`)
        assert.ok(!result.stderr.includes('token-value'), label)
        assert.ok(before.program.equals(bytes(path.join(root, 'lib/kk-studio-daemon'))), label)
        assert.ok(before.config.equals(bytes(path.join(root, 'daemon.json'))), label)
        assert.ok(before.token.equals(bytes(path.join(root, 'daemon.token'))), label)
        assert.ok(existsSync(path.join(fixture.state, 'active')), 'the running service must stay untouched')
        assert.ok(!existsSync(path.join(root, '.work')), 'no staging may precede input revalidation')
        assert.ok(!existsSync(path.join(fixture.installRoot, 'backups')), 'no backup directory exists')
      }
    }))

  it('a failed reinstall keeps the running installation unchanged', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const files = [path.join(root, 'daemon.json'), path.join(root, 'daemon.token'), path.join(root, 'lib/kk-studio-daemon'), unitPath(fixture)]
      const before = files.map((file) => bytes(file))
      const result = install({ FAKE_DAEMON_VERSION: '9.9.9' })
      assert.notEqual(result.status, 0)
      files.forEach((file, index) => assert.ok(before[index].equals(bytes(file)), file))
      assert.ok(existsSync(path.join(fixture.state, 'active')), 'the running service must not be stopped')
      assert.ok(!existsSync(path.join(fixture.installRoot, 'backups')))
    }))

  it('rejects unsafe managed path permissions without repairing them', () =>
    withInstall(({ fixture, install }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      const program = bytes(path.join(root, 'lib/kk-studio-daemon'))
      chmodSync(root, 0o770)
      const result = install()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('unsafe permissions'), result.stderr)
      assert.equal(mode(root), 0o770)
      assert.ok(program.equals(bytes(path.join(root, 'lib/kk-studio-daemon'))))
      assert.ok(existsSync(path.join(fixture.state, 'active')))
    }))

  it('rejects a foreign service definition without touching it', () =>
    withInstall(({ fixture, install }) => {
      const unit = unitPath(fixture)
      mkdirSync(path.dirname(unit), { recursive: true })
      writeFileSync(unit, fixture.hostOs === 'Linux'
        ? `${managedMarker(fixture)}\n[Service]\nExecStart=/bin/true\n`
        : `<?xml version="1.0" encoding="UTF-8"?>\n${managedMarker(fixture)}\n<plist version="1.0"><dict/></plist>\n`)
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
      // Run with the default locale and a C locale: Bash 3 must keep the UTF-8 diagnostic and a
      // non-zero status without treating the adjacent punctuation as part of the variable name.
      for (const extraEnv of [{}, { LC_ALL: 'C', LANG: 'C' }]) {
        const result = install(extraEnv)
        assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`)
        for (const expected of ['belongs to another user', unit, '不要伪造所有权标记']) {
          assert.ok(result.stderr.includes(expected), expected)
        }
        assert.ok(!result.stderr.includes('token-value'))
      }
      assert.ok(before.equals(bytes(unit)))
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
    }))

  it('rejects a definition that does not run this card program', () =>
    withInstall(({ fixture, install }) => {
      const unit = unitPath(fixture)
      const other = path.join(fixture.installRoot, 'environments', SECOND_ENVIRONMENT_ID)
      mkdirSync(path.dirname(unit), { recursive: true })
      const lines = fixture.hostOs === 'Linux'
        ? [
            managedMarker(fixture),
            '[Service]',
            `ExecStart="${other}/lib/kk-studio-daemon" --config "${other}/daemon.json"`,
          ]
        : [
            '<?xml version="1.0" encoding="UTF-8"?>',
            managedMarker(fixture),
            '<plist version="1.0"><dict><key>ProgramArguments</key><array>',
            `<string>${other}/lib/kk-studio-daemon</string>`,
            '<string>--config</string>',
            `<string>${other}/daemon.json</string>`,
            '</array></dict></plist>',
          ]
      writeFileSync(unit, lines.join('\n') + '\n')
      const result = install()
      assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`)
      assert.match(result.stderr, /does not run this environment program/)
      assert.ok(existsSync(unit))
      assert.ok(!existsSync(path.join(envRoot(fixture), 'lib')))
    }))

  it('rejects a program directory without the owned service', () =>
    withInstall(({ fixture, install }) => {
      const root = envRoot(fixture)
      mkdirSync(path.join(root, 'lib'), { recursive: true, mode: 0o700 })
      writeFileSync(path.join(root, 'lib/unknown'), 'unknown')
      const result = install()
      assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`)
      assert.match(result.stderr, /program directory exists without the owned service/)
      assert.ok(existsSync(path.join(root, 'lib/unknown')))
      assert.ok(!existsSync(path.join(fixture.state, 'active')))
    }))

  it('update failure keeps config, credentials, skills and the previous program', () =>
    withInstall(({ fixture, install, update }) => {
      assert.equal(install().status, 0)
      const root = envRoot(fixture)
      mkdirSync(path.join(root, 'skills/skill-a'), { recursive: true, mode: 0o700 })
      chmodSync(path.join(root, 'skills'), 0o700)
      writeFileSync(path.join(root, 'skills/skill-a/SKILL.md'), 'user data')
      const config = daemonJson({ environmentId: ENVIRONMENT_ID })
      writeFileSync(path.join(root, 'daemon.json'), config)
      writeFileSync(path.join(root, 'lib/previous-marker'), 'previous')
      const result = update({ FAKE_DAEMON_VERSION: '9.9.9' })
      assert.notEqual(result.status, 0)
      assert.equal(readFileSync(path.join(root, 'daemon.json'), 'utf8'), config)
      assert.ok(existsSync(path.join(root, 'skills/skill-a/SKILL.md')))
      assert.ok(existsSync(path.join(root, 'lib/previous-marker')))
      const record = JSON.parse(readFileSync(path.join(root, 'last-operation.json'), 'utf8'))
      assert.deepEqual(Object.keys(record).sort(), [
        'errorCode', 'operationId', 'phase', 'result', 'targetVersion', 'time', 'type',
      ])
      // 取得操作锁之前的失败不写本卡记录：另一个进程可能正在操作同一张卡片。
      assert.equal(record.type, 'install')
      assert.equal(record.result, 'succeeded')
      assert.ok(JSON.stringify(record).length <= 4096)
      assert.doesNotMatch(JSON.stringify(record), /token-value|user data/)
    }))
})
