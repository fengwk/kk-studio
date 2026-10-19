/**
 * Contract tests for `scripts/daemon/smoke-archive.sh`.
 *
 * These build lightweight fake archives with a shimmed frozen runtime so every failure boundary of
 * the smoke harness is exercised by the real script: name/platform rejection, missing archive
 * entries, a wrong frozen Node major, a manifest/version mismatch, wrong `--version`/`--help`
 * output, a valid configuration the runtime rejects, an invalid or wrapped configuration the
 * runtime accepts, and a failing koffi probe. They also assert the resource-release contract: on
 * timeout and on SIGTERM the harness must terminate and reap the daemon it started before deleting
 * its temporary directory.
 *
 * The fake runtime is a shim that only satisfies the harness's own call shapes; it does not prove
 * product behaviour. The real archive smoke (built by `prepare-release.sh` and run in the release
 * pipeline) is what proves the product.
 */

import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import { DAEMON_VERSION, withTempDir } from './support/release-fixture.mjs'
import { ARCHIVE_NAME, SMOKE, buildArchive, entriesWithPrefix, hostArch, hostOs, isUnixHost, shim } from './support/smoke-archive.mjs'

const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis))
const skip = !isUnixHost() && 'smoke-archive.sh runs on Linux and macOS'

/** Run `body(context)` with a fresh temporary root and helpers to build/run fake archives. */
function withSmoke(body) {
  return withTempDir((root) => {
    const arch = hostArch()
    const otherArch = arch === 'amd64' ? 'arm64' : 'amd64'
    const context = {
      root,
      arch,
      otherArch,
      archive: ({ name, ...options } = {}) => {
        const { includeRuntime, includeMain, includeKoffi, includeIde, includeEntry, includeNative, ...shimOptions } = options
        return buildArchive(path.join(root, name ?? ARCHIVE_NAME(arch)), {
          node: shim(shimOptions),
          includeRuntime,
          includeMain,
          includeKoffi,
          includeIde,
          includeEntry,
          includeNative,
        })
      },
      run: (archive, { tmpdir, timeout = 60_000 } = {}) => {
        const env = { ...process.env }
        if (tmpdir) {
          env.TMPDIR = tmpdir
        }
        return spawnSync('bash', [SMOKE, archive], { env, encoding: 'utf8', timeout })
      },
    }
    return body(context)
  })
}

async function assertPidReaped(pid) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      process.kill(pid, 0)
    } catch (error) {
      if (error.code === 'ESRCH') {
        return
      }
      throw error
    }
    await sleep(100)
  }
  assert.fail(`the daemon process ${pid} was not terminated`)
}

const pidOf = (file) => Number(readFileSync(file, 'utf8'))

describe('smoke-archive.sh', { skip }, () => {
  it('rejects wrong arguments', () =>
    withSmoke(({ root, arch, run }) => {
      assert.equal(spawnSync('bash', [SMOKE, '--help']).status, 0)
      assert.equal(spawnSync('bash', [SMOKE]).status, 2)
      const absent = run(path.join(root, ARCHIVE_NAME(arch)))
      assert.notEqual(absent.status, 0)
      assert.ok(absent.stderr.includes('readable non-empty file'))
    }))

  it('rejects a windows archive name', () =>
    withSmoke(({ root, run }) => {
      const archive = path.join(root, 'kk-studio-daemon-v1.0.14-windows-amd64.tar.gz')
      writeFileSync(archive, 'present but not a unix archive')
      const result = run(archive)
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('linux or macos archive'))
    }))

  it('rejects a cross-arch archive', () =>
    withSmoke(({ otherArch, archive, run }) => {
      const result = run(archive({ name: ARCHIVE_NAME(otherArch) }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('cross-platform archives cannot run here'))
    }))

  it('rejects a corrupt archive', () =>
    withSmoke(({ root, arch, run }) => {
      const corrupt = path.join(root, ARCHIVE_NAME(arch))
      writeFileSync(corrupt, 'not a gzip archive')
      const result = run(corrupt)
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('cannot extract the archive'))
    }))

  it('rejects missing archive entries', () =>
    withSmoke(({ archive, run }) => {
      const cases = {
        'missing runtime': { includeRuntime: false },
        'missing entrypoint': { includeMain: false },
        'missing koffi': { includeKoffi: false },
        'missing IDE extension': { includeIde: false },
        'missing code-server entry': { includeEntry: false },
      }
      for (const [label, flags] of Object.entries(cases)) {
        const file = archive(flags)
        const result = run(file)
        assert.notEqual(result.status, 0, label)
        assert.ok(result.stderr.includes('archive is missing'), label)
        rmSync(file)
      }
    }))

  it('rejects a wrong frozen node major', () =>
    withSmoke(({ archive, run }) => {
      const result = run(archive({ nodeVersion: 'v22.0.0' }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('must be Node 24.x'))
    }))

  it('rejects a manifest version mismatch', () =>
    withSmoke(({ archive, run }) => {
      const result = run(archive({ manifestVersion: '9.9.9' }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('runtime-manifest daemonVersion'))
    }))

  it('rejects a wrong cli version and help', () =>
    withSmoke(({ arch, archive, run }) => {
      let result = run(archive({ cliVersion: 'kk-studio-daemon 9.9.9' }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('--version mismatch'))

      result = run(archive({ name: `kk-studio-daemon-v1.0.14-help-${hostOs()}-${arch}.tar.gz`, helpOk: false }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('--help'))
    }))

  it('rejects config boundaries', () =>
    withSmoke(({ archive, run }) => {
      let result = run(archive({ validOk: false }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('rejected a valid configuration'))

      result = run(archive({ invalidRejected: false }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('invalid configuration must be rejected'))

      result = run(archive({ wrappedRejected: false }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('wrapped daemon object must be rejected'))
    }))

  it('rejects a native module built for another platform', () =>
    withSmoke(({ archive, run }) => {
      const result = run(archive({ nativeForeign: true }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('native modules failed to load'))
    }))

  it('rejects a code-server native module that does not load', () =>
    withSmoke(({ archive, run }) => {
      const result = run(archive({ nativeOk: false }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('native modules failed to load'))
    }))

  it('rejects a failing koffi probe', () =>
    withSmoke(({ archive, run }) => {
      const result = run(archive({ probeExit: 4, probeOut: 'boom' }))
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('koffi directory lock probe failed'))
    }))

  it('the success path terminates and reaps the daemon', () =>
    withSmoke(async ({ root, archive, run }) => {
      const pidFile = path.join(root, 'daemon.pid')
      const tmpdir = path.join(root, 'tmp')
      mkdirSync(tmpdir)
      const result = run(archive({ configMode: 'hold', pidFile }), { tmpdir })
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
      assert.ok(result.stdout.includes('archive smoke ok'))
      await assertPidReaped(pidOf(pidFile))
      assert.deepEqual(entriesWithPrefix(tmpdir, 'kk-studio-daemon-smoke.'), [], 'temporary directory must be removed')
    }))

  it('a lock timeout is bounded and terminates the daemon', () =>
    withSmoke(async ({ root, archive, run }) => {
      // The fixture never creates a lock; the harness must give up and still reap the daemon.
      const pidFile = path.join(root, 'timeout.pid')
      const tmpdir = path.join(root, 'tmp-timeout')
      mkdirSync(tmpdir)
      const result = run(archive({ configMode: 'sleep-no-lock', pidFile }), { tmpdir, timeout: 60_000 })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('did not acquire its data lock'))
      await assertPidReaped(pidOf(pidFile))
      assert.deepEqual(entriesWithPrefix(tmpdir, 'kk-studio-daemon-smoke.'), [])
    }))

  it('SIGTERM is forwarded and the daemon is reaped', () =>
    withSmoke(async ({ root, archive }) => {
      const pidFile = path.join(root, 'signal.pid')
      const tmpdir = path.join(root, 'tmp-signal')
      mkdirSync(tmpdir)
      const archiveFile = archive({ configMode: 'hold', pidFile })
      const child = spawn('bash', [SMOKE, archiveFile], { env: { ...process.env, TMPDIR: tmpdir } })
      let output = ''
      child.stdout.on('data', (chunk) => {
        output += chunk
      })
      child.stderr.on('data', (chunk) => {
        output += chunk
      })
      const closed = new Promise((resolve) => child.once('close', (status) => resolve(status)))
      try {
        let locked = false
        for (let attempt = 0; attempt < 200 && !locked; attempt += 1) {
          locked = entriesWithPrefix(tmpdir, 'kk-studio-daemon-smoke.').some((name) =>
            existsSync(path.join(tmpdir, name, 'run/daemon.lock')),
          )
          assert.equal(child.exitCode, null, `harness exited early: ${output}`)
          if (!locked) {
            await sleep(50)
          }
        }
        assert.ok(locked, 'the fixture daemon never acquired its lock')
        child.kill('SIGTERM')
        const status = await closed
        assert.equal(status, 143, output)
      } finally {
        child.kill('SIGKILL')
      }
      await assertPidReaped(pidOf(pidFile))
      assert.deepEqual(entriesWithPrefix(tmpdir, 'kk-studio-daemon-smoke.'), [])
    }))

  it('a daemon that ignores SIGTERM is not reported as success', () =>
    withSmoke(async ({ root, archive, run }) => {
      // A daemon that requires SIGKILL must not produce a green smoke: the success path only accepts
      // a graceful SIGTERM exit, while the process is still forcibly reaped.
      const pidFile = path.join(root, 'ignore.pid')
      const tmpdir = path.join(root, 'tmp-ignore')
      mkdirSync(tmpdir)
      const result = run(archive({ configMode: 'ignore-term', pidFile }), { tmpdir, timeout: 60_000 })
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('must exit gracefully on SIGTERM'))
      assert.ok(!result.stdout.includes('archive smoke ok'))
      await assertPidReaped(pidOf(pidFile))
      assert.deepEqual(entriesWithPrefix(tmpdir, 'kk-studio-daemon-smoke.'), [])
    }))

  it('keeps the default daemon version wired into the shim', () => {
    assert.ok(shim().includes(DAEMON_VERSION))
  })
})
