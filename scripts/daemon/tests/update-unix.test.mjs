/**
 * Managed UNIX update script contracts extracted from the shipped Node Daemon source.
 *
 * `daemon/src/update-scripts.ts` is the only source of the UNIX update script. The tests import
 * `UNIX_UPDATE_SCRIPT` from that module through Node and execute it with the system Bash, so there
 * is no second copy of the update facts and no Java/JAR fixture.
 *
 * Covered: the handoff identity/state gate, the private permit wait (missing, symlinked and
 * directory permits fail closed; a late permit is awaited), managed unit/agent ownership,
 * stop/backup/replace/restart ordering and failures, the unique backup that never reuses a previous
 * one, and the fact that every rejected input performs zero stop/backup/replace.
 */

import assert from 'node:assert/strict'
import { existsSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'

import { LAUNCHD_LABEL, SERVICE_NAME, sleep, withUpdateFixtures } from './support/update-fixture.mjs'

const INSTALLED = 'installed-release-main-js\n'
const STAGED = 'staged-release-main-js\n'
const text = (file) => readFileSync(file, 'utf8')
const installedMain = (fixture) => text(path.join(fixture.installed, 'main.js'))

function assertFailed(fixture, result, message) {
  assert.notEqual(result.status, 0, result.stdout + result.stderr)
  const document = fixture.resultDocument()
  assert.equal(document.phase, 'FAILED')
  assert.equal(document.operationId, fixture.operationId)
  assert.ok(document.message.includes(message), document.message)
}

/** A rejected handoff/permit must produce zero stop, backup or replace. */
function assertNoDestructiveAction(fixture) {
  assert.deepEqual(fixture.backupDirectories(), [])
  assert.equal(installedMain(fixture), INSTALLED)
  assert.ok(existsSync(path.join(fixture.staged, 'main.js')), 'the staged distribution must not be consumed')
  for (const call of fixture.calls()) {
    assert.ok(!` ${call} `.includes(' stop '), 'no service may be stopped')
    assert.ok(!call.includes('bootout'), 'no launch agent may be booted out')
  }
}

const OTHER_OPERATION = '22222222-2222-2222-2222-222222222222'

describe('handoff gate: only an operation with a matching private permit and an ACCEPTED handoff may replace anything', () => {
  it('missing handoff performs no destructive action', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ handoffState: 'missing' })
      assertFailed(fixture, fixture.run(), 'handoff metadata is missing')
      assertNoDestructiveAction(fixture)
    }))

  it('mismatched operation performs no destructive action', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ handoffOperation: OTHER_OPERATION })
      assertFailed(fixture, fixture.run(), 'handoff metadata does not match the operation')
      assertNoDestructiveAction(fixture)
    }))

  for (const state of ['PREPARING', 'FAILED']) {
    it(`a ${state} handoff performs no destructive action`, () =>
      withUpdateFixtures(({ prepared }) => {
        const fixture = prepared({ handoffState: state })
        assertFailed(fixture, fixture.run(), 'handoff metadata is not accepted')
        assertNoDestructiveAction(fixture)
      }))
  }

  it('an early PREPARING without a permit fails closed at the permit wait', () =>
    withUpdateFixtures(({ prepared }) => {
      // The job starts while the daemon still persists PREPARING, so the operation identity alone
      // is checked first: an early PREPARING must reach the permit wait (and only the missing
      // permit may fail) instead of being rejected as an identity mismatch.
      const fixture = prepared({ handoffState: 'PREPARING', permit: 'absent', fastSleep: true })
      assertFailed(fixture, fixture.run(), 'the daemon did not release the update permit in time')
      assertNoDestructiveAction(fixture)
    }))

  it('a permit naming another operation is never a release for this one', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      writeFileSync(fixture.permit, `${OTHER_OPERATION}\n`)
      assertFailed(fixture, fixture.run(), 'the update permit does not match the operation')
      assertNoDestructiveAction(fixture)
    }))

  it('an empty permit performs no destructive action', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      writeFileSync(fixture.permit, '')
      assertFailed(fixture, fixture.run(), 'the update permit does not match the operation')
      assertNoDestructiveAction(fixture)
    }))

  it('a handoff identity change during the permit wait is rejected', () =>
    withUpdateFixtures(async ({ prepared }) => {
      // The snapshot is re-read after the permit: an identity that changed while waiting must still
      // fail closed with zero stop/backup/replace.
      const fixture = prepared({ permit: 'absent' })
      const { finished } = fixture.start()
      await sleep(500)
      fixture.writeHandoff('ACCEPTED', OTHER_OPERATION)
      writeFileSync(fixture.permit, `${fixture.operationId}\n`)
      const result = await finished
      assert.notEqual(result.status, 0, result.stdout + result.stderr)
      assert.equal(fixture.resultDocument().phase, 'FAILED')
      assert.ok(fixture.resultDocument().message.includes('handoff metadata does not match the operation'))
      assertNoDestructiveAction(fixture)
    }))

  it('an early PREPARING handoff applies once the permit arrives after acceptance', () =>
    withUpdateFixtures(async ({ prepared }) => {
      // The production sequence: the job starts with PREPARING, the daemon then freezes ACCEPTED and
      // releases the permit, and only then does the job stop and replace.
      const fixture = prepared({ handoffState: 'PREPARING', permit: 'absent' })
      const { finished } = fixture.start()
      await sleep(500)
      assert.ok(!existsSync(fixture.result), 'an early PREPARING must not report a failure')
      fixture.writeHandoff('ACCEPTED')
      writeFileSync(fixture.permit, `${fixture.operationId}\n`)
      const result = await finished
      assert.equal(result.status, 0, result.stdout + result.stderr)
      assert.equal(fixture.resultDocument().phase, 'PREPARED')
      assert.ok(fixture.calls().includes(`systemctl --user stop ${SERVICE_NAME}`))
      assert.equal(installedMain(fixture), STAGED)
    }))

  it('a missing permit times out without destructive action', () =>
    withUpdateFixtures(({ prepared }) => {
      // Negative control for the permit gate: no permit means no replacement, even with an ACCEPTED handoff.
      const fixture = prepared({ permit: 'absent', fastSleep: true })
      assertFailed(fixture, fixture.run(), 'the daemon did not release the update permit in time')
      assertNoDestructiveAction(fixture)
    }))

  it('a directory permit is not a release', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ permit: 'directory', fastSleep: true })
      assertFailed(fixture, fixture.run(), 'the daemon did not release the update permit in time')
      assertNoDestructiveAction(fixture)
    }))

  it('a symlinked permit is rejected', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ permit: 'symlink' })
      assertFailed(fixture, fixture.run(), 'the update permit is not a regular file')
      assertNoDestructiveAction(fixture)
    }))

  it('a late permit is awaited then applied', () =>
    withUpdateFixtures(async ({ prepared }) => {
      const fixture = prepared({ permit: 'absent' })
      const { finished } = fixture.start()
      // The script must be blocked in its permit wait; the permit is released afterwards.
      await sleep(500)
      writeFileSync(fixture.permit, `${fixture.operationId}\n`)
      const result = await finished
      assert.equal(result.status, 0, result.stdout + result.stderr)
      assert.equal(fixture.resultDocument().phase, 'PREPARED')
      assert.ok(fixture.calls().includes(`systemctl --user stop ${SERVICE_NAME}`))
    }))

  it('an early PREPARING handoff is not rejected after acceptance', () =>
    withUpdateFixtures(({ prepared }) => {
      // A permit is not released until the daemon successfully starts the job and freezes ACCEPTED,
      // so a later re-run sees the accepted handoff and applies normally.
      const fixture = prepared({ handoffState: 'PREPARING', permit: 'absent' })
      fixture.writeHandoff('ACCEPTED')
      writeFileSync(fixture.permit, `${fixture.operationId}\n`)
      const result = fixture.run()
      assert.equal(result.status, 0, result.stderr)
      assert.equal(fixture.resultDocument().phase, 'PREPARED')
    }))
})

describe('systemd update', () => {
  const stop = `systemctl --user stop ${SERVICE_NAME}`
  const start = `systemctl --user start ${SERVICE_NAME}`

  it('success backs up, replaces and restarts the managed service', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      const result = fixture.run()
      assert.equal(result.status, 0, result.stderr)
      assert.equal(fixture.resultDocument().phase, 'PREPARED')
      assert.ok(!existsSync(fixture.staged), 'staged distribution must be consumed')
      assert.equal(installedMain(fixture), STAGED)
      const backups = fixture.backupDirectories()
      assert.equal(backups.length, 1)
      assert.equal(text(path.join(backups[0], 'previous/main.js')), INSTALLED)
      const calls = fixture.calls()
      assert.ok(calls.includes(stop))
      assert.ok(calls.includes(start))
      assert.ok(calls.indexOf(stop) < calls.indexOf(start))
    }))

  it('each run creates a unique backup and keeps the previous one', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      assert.equal(fixture.run().status, 0)
      // Reset the staged input for a second applied update of the same operation.
      mkdirSync(fixture.staged)
      writeFileSync(path.join(fixture.staged, 'main.js'), 'second-staged-main-js\n')
      assert.equal(fixture.run().status, 0)
      const backups = fixture.backupDirectories()
      assert.equal(backups.length, 2, 'every run must leave its own unique backup')
      assert.deepEqual(
        new Set(backups.map((backup) => text(path.join(backup, 'previous/main.js')))),
        new Set([INSTALLED, STAGED]),
      )
    }))

  it('a missing staged distribution is reported without touching installed', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      unlinkSync(path.join(fixture.staged, 'main.js'))
      assertFailed(fixture, fixture.run(), 'staged distribution is missing')
      assert.equal(installedMain(fixture), INSTALLED)
    }))

  it('a missing installed distribution is reported', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      rmSync(fixture.installed, { recursive: true })
      assertFailed(fixture, fixture.run(), 'installed distribution is missing')
    }))

  it('a missing unit is reported before any destructive action', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      assertFailed(fixture, fixture.run(), 'managed systemd unit is missing')
      assert.ok(existsSync(fixture.staged), 'no destructive action before ownership check')
    }))

  it('a unit not owned by the installer is rejected', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      fixture.writeServiceDefinition("# someone else's unit")
      assertFailed(fixture, fixture.run(), 'systemd unit is not owned by the installer')
      assert.equal(installedMain(fixture), INSTALLED)
    }))

  it('a stop failure is reported', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ serviceMode: 'stop-fail' })
      assertFailed(fixture, fixture.run(), 'managed service did not stop')
    }))

  it('the stop wait times out when the service stays active', () =>
    withUpdateFixtures(({ prepared }) => {
      // `systemctl stop` returns success but the unit never becomes inactive: the bounded wait must
      // fail closed instead of replacing a running program's files.
      const fixture = prepared({ serviceMode: 'stop-stuck', fastSleep: true })
      assertFailed(fixture, fixture.run(), 'managed service did not stop')
      assert.deepEqual(fixture.backupDirectories(), [])
      assert.equal(installedMain(fixture), INSTALLED)
    }))

  it('a backup directory creation failure leaves installed untouched', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ mktempMode: 'fail' })
      assertFailed(fixture, fixture.run(), 'could not create the backup directory')
      assert.equal(installedMain(fixture), INSTALLED)
    }))

  it('a backup move failure leaves installed untouched', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ mvMode: 'backup-fail' })
      assertFailed(fixture, fixture.run(), 'could not back up the installed distribution')
      assert.equal(installedMain(fixture), INSTALLED)
      assert.ok(existsSync(path.join(fixture.staged, 'main.js')))
    }))

  it('a replace failure keeps the previous distribution in its backup', () =>
    withUpdateFixtures(({ prepared }) => {
      // There is no automatic rollback: the old distribution stays in the unique backup, the staged
      // distribution stays intact, the service stays stopped and recovery is manual.
      const fixture = prepared({ mvMode: 'replace-fail' })
      assertFailed(fixture, fixture.run(), 'could not replace the installed distribution')
      const backups = fixture.backupDirectories()
      assert.equal(backups.length, 1)
      assert.equal(text(path.join(backups[0], 'previous/main.js')), INSTALLED)
      assert.ok(!existsSync(fixture.installed), 'the backup move already vacated the target')
      assert.ok(existsSync(path.join(fixture.staged, 'main.js')))
      assert.ok(fixture.calls().includes(stop))
      assert.ok(!fixture.calls().includes(start))
    }))

  it('an unexpected exit leaves a durable FAILED receipt', () =>
    withUpdateFixtures(({ prepared }) => {
      // `os=$(uname -s)` is outside fail_closed: an abort there must still leave a durable,
      // de-sensitised FAILED receipt through the EXIT trap, with zero destructive action.
      const fixture = prepared({ unameMode: 'fail' })
      const result = fixture.run()
      assert.notEqual(result.status, 0)
      assert.equal(fixture.resultDocument().phase, 'FAILED')
      assert.ok(fixture.resultDocument().message.includes('update aborted unexpectedly (exit'))
      assertNoDestructiveAction(fixture)
    }))

  it('an unwritable FAILED receipt is reported without masking the reason', () =>
    withUpdateFixtures(({ prepared }) => {
      // A receipt that cannot be written must be reported, must not truncate the diagnostic and must
      // not hide the fail-closed decision.
      const fixture = prepared({ handoffState: 'missing', resultMode: 'unwritable' })
      const result = fixture.run()
      assert.notEqual(result.status, 0)
      assert.ok(result.stderr.includes('could not write the FAILED receipt'))
      assert.ok(result.stderr.includes('failed: handoff metadata is missing'))
      assertNoDestructiveAction(fixture)
    }))

  it('an unwritable PREPARED receipt does not falsify a completed update', () =>
    withUpdateFixtures(({ prepared }) => {
      // The distribution is already replaced and the service restarted, so a receipt that cannot be
      // written is reported but never turned into a false failure.
      const fixture = prepared({ resultMode: 'unwritable' })
      const result = fixture.run()
      assert.equal(result.status, 0, result.stdout + result.stderr)
      assert.ok(result.stderr.includes('could not write the PREPARED receipt'))
      assert.ok(!result.stderr.includes('aborted unexpectedly'))
      assert.ok(!existsSync(fixture.staged), 'the staged distribution must be consumed')
      assert.equal(installedMain(fixture), STAGED)
      assert.ok(fixture.calls().includes(start))
    }))

  it('a restart failure reports and keeps the replaced distribution', () =>
    withUpdateFixtures(({ prepared }) => {
      // The update never rolls back automatically: the new distribution is installed, the failure is
      // reported and the previous distribution remains in the backup directory.
      const fixture = prepared({ serviceMode: 'start-fail' })
      assertFailed(fixture, fixture.run(), 'could not restart the managed service')
      assert.equal(installedMain(fixture), STAGED)
      const backups = fixture.backupDirectories()
      assert.equal(backups.length, 1)
      assert.equal(text(path.join(backups[0], 'previous/main.js')), INSTALLED)
    }))
})

describe('launchd update', () => {
  it('success boots out and bootstraps the managed agent', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin' })
      const result = fixture.run()
      assert.equal(result.status, 0, result.stderr)
      assert.equal(fixture.resultDocument().phase, 'PREPARED')
      const calls = fixture.calls()
      assert.ok(calls.includes(`launchctl bootout ${fixture.domain}/${LAUNCHD_LABEL}`))
      assert.ok(calls.includes(`launchctl bootstrap ${fixture.domain} ${fixture.plist}`))
    }))

  it('an agent not owned by the installer is rejected', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create({ operatingSystem: 'Darwin' })
      fixture.writeServiceDefinition("<!-- someone else's agent -->")
      assertFailed(fixture, fixture.run(), 'launch agent is not owned by the installer')
    }))

  it('the stop wait times out when the agent stays loaded', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin', serviceMode: 'stop-stuck', fastSleep: true })
      assertFailed(fixture, fixture.run(), 'managed launch agent did not stop')
      assert.deepEqual(fixture.backupDirectories(), [])
    }))

  it('a restart failure is reported', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin', serviceMode: 'start-fail' })
      assertFailed(fixture, fixture.run(), 'could not restart the managed launch agent')
    }))
})
