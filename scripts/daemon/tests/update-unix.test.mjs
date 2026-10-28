/**
 * Managed UNIX update script contracts extracted from the shipped Node Daemon source.
 *
 * The script owns only two things: launching the independent updater (`run`) and stopping or
 * starting the installer-owned systemd/launchd service. Handoff, permit, replacement and result
 * state belong to the Node updater and are covered by daemon/test/update-entry.test.ts; these
 * shell tests must not recreate that state machine.
 *
 * Covered here: exact private-updater argv and exit propagation, systemd/launchd stop and start,
 * installer ownership markers, foreign/missing definitions failing closed before any service
 * command, stop wait timeout, and start/stop failure boundaries.
 */

import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { nodeExport } from './support/release-fixture.mjs'
import {
  LAUNCHD_LABEL,
  PLIST_MARKER,
  SERVICE_NAME,
  UNIT_MARKER,
  withUpdateFixtures,
} from './support/update-fixture.mjs'

const scriptText = nodeExport('UNIX_UPDATE_SCRIPT')
const describePosix = process.platform === 'win32' ? describe.skip : describe

function assertFailed(result, message) {
  assert.notEqual(result.status, 0, result.stdout + result.stderr)
  assert.ok(result.stderr.includes(message), result.stderr)
}

function assertNoServiceMutation(calls) {
  for (const call of calls) {
    assert.ok(!/systemctl .* (stop|start) /u.test(call), `unexpected service mutation: ${call}`)
    assert.ok(!/launchctl (bootout|bootstrap) /u.test(call), `unexpected service mutation: ${call}`)
  }
}

function assertStoppedWithoutStarting(calls) {
  assert.ok(
    calls.some((call) => call === `systemctl --user stop ${SERVICE_NAME}` || call.startsWith('launchctl bootout ')),
    calls.join('\n'),
  )
  assert.ok(!calls.some((call) => /systemctl .* start |launchctl bootstrap /u.test(call)), calls.join('\n'))
}

function launchAgentPath(fixture) {
  return `${fixture.domain}/${LAUNCHD_LABEL}`
}

describe('UNIX update source: run and service actions remain separate security boundaries', () => {
  it('launches only the private updater from the run action', () => {
    assert.match(scriptText, /if \[ "\$action" = "run" \]; then/u)
    assert.match(scriptText, /\[ "\$#" -eq 6 \]/u)
    assert.match(scriptText, /exec "\$updater_node" "\$updater_entry" \\/u)
    for (const flag of ['--operation-dir', '--staged-dir', '--installed-dir']) {
      assert.ok(scriptText.includes(flag), flag)
    }
    assert.ok(scriptText.includes('--update-script "$0"'))
    assert.match(scriptText, /\[ "\$action" = "stop" \] \|\| \[ "\$action" = "start" \]/u)
  })

  it('checks the installer marker before either service action', () => {
    assert.ok(scriptText.includes(`UNIT_MARKER='${UNIT_MARKER}'`))
    assert.ok(scriptText.includes(`PLIST_MARKER='${PLIST_MARKER}'`))
    assert.match(scriptText, /systemctl --user stop "\$SERVICE_NAME"/u)
    assert.match(scriptText, /systemctl --user start "\$SERVICE_NAME"/u)
    assert.match(scriptText, /launchctl bootout "\$domain\/\$LAUNCHD_LABEL"/u)
    assert.match(scriptText, /launchctl bootstrap "\$domain" "\$plist"/u)
    assert.ok(scriptText.includes('if [ "$waited" -ge "$STOP_WAIT_SECONDS" ]; then'))
  })

  it('does not read credentials or own handoff/replacement state', () => {
    assert.doesNotMatch(scriptText, /handoff|permit|update-result|backup/u)
    assert.doesNotMatch(scriptText, /daemon\.token|ReadAllText|ConvertFrom-SecureString/u)
    assert.doesNotMatch(scriptText, /source |eval /u)
  })
})

describePosix('UNIX update run action: only the private updater is launched', () => {
  it('execs the updater with fixed absolute paths and propagates its exit code', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      const result = fixture.run('run', { env: { FAKE_UPDATER_EXIT: '7' } })
      assert.equal(result.status, 7, result.stderr)
      const expected = [
        'updater',
        fixture.updaterEntry,
        '--operation-dir',
        fixture.updateDir,
        '--staged-dir',
        fixture.staged,
        '--installed-dir',
        fixture.installed,
        '--update-script',
        fixture.script,
      ].join('|')
      const calls = fixture.calls()
      assert.deepEqual(calls, [expected])
      assertNoServiceMutation(calls)
    }))

  it('rejects run without both updater arguments', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      const result = fixture.invoke([
        fixture.script,
        'run',
        fixture.updateDir,
        fixture.staged,
        fixture.installed,
      ])
      assert.equal(result.status, 2)
      assert.match(result.stderr, /run requires updater node and entry/u)
      assert.deepEqual(fixture.calls(), [])
    }))

  it('rejects an unknown action before touching a service', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      const result = fixture.invoke([
        fixture.script,
        'pause',
        fixture.updateDir,
        fixture.staged,
        fixture.installed,
      ])
      assert.equal(result.status, 2)
      assert.match(result.stderr, /unknown update script action/u)
      assert.deepEqual(fixture.calls(), [])
    }))
})

describePosix('systemd update:', () => {
  it('stop stops the installer-owned unit and never starts it', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      const result = fixture.run('stop')
      assert.equal(result.status, 0, result.stderr)
      const calls = fixture.calls()
      assert.equal(calls[0], `systemctl --user stop ${SERVICE_NAME}`)
      assert.ok(calls.some((call) => call.includes('is-active')), calls.join('\n'))
      assertStoppedWithoutStarting(calls)
    }))

  it('start starts the installer-owned unit and never stops it', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared()
      const result = fixture.run('start', { active: false })
      assert.equal(result.status, 0, result.stderr)
      const calls = fixture.calls()
      assert.deepEqual(calls, [`systemctl --user start ${SERVICE_NAME}`])
    }))

  it('a missing unit fails closed before any service command', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      assertFailed(fixture.run('stop'), 'managed systemd unit is missing')
      assert.deepEqual(fixture.calls(), [])
    }))

  it('a unit not owned by the installer fails closed before any service command', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create()
      fixture.writeServiceDefinition("# someone else's unit")
      assertFailed(fixture.run('stop'), 'systemd unit is not owned by the installer')
      assert.deepEqual(fixture.calls(), [])
    }))

  it('a stop failure is reported and never becomes a start', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ serviceMode: 'stop-fail' })
      assertFailed(fixture.run('stop'), 'managed service did not stop')
      assertStoppedWithoutStarting(fixture.calls())
    }))

  it('the stop wait times out when the unit stays active', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ serviceMode: 'stop-stuck' })
      assertFailed(fixture.run('stop'), 'managed service did not stop')
      const calls = fixture.calls()
      assert.ok(calls.filter((call) => call.includes('is-active')).length > 1, calls.join('\n'))
      assertStoppedWithoutStarting(calls)
    }))

  it('a start failure is reported and never becomes a stop', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ serviceMode: 'start-fail' })
      assertFailed(fixture.run('start', { active: false }), 'could not restart the managed service')
      assert.deepEqual(fixture.calls(), [`systemctl --user start ${SERVICE_NAME}`])
    }))
})

describePosix('launchd update:', () => {
  it('stop boots out the installer-owned agent and never bootstraps it', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin' })
      const result = fixture.run('stop')
      assert.equal(result.status, 0, result.stderr)
      const calls = fixture.calls()
      assert.equal(calls[0], `launchctl bootout ${launchAgentPath(fixture)}`)
      assert.ok(calls.some((call) => call.startsWith('launchctl print ')), calls.join('\n'))
      assertStoppedWithoutStarting(calls)
    }))

  it('start bootstraps the installer-owned agent and never boots it out', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin' })
      const result = fixture.run('start', { active: false })
      assert.equal(result.status, 0, result.stderr)
      assert.deepEqual(fixture.calls(), [
        `launchctl bootstrap ${fixture.domain} ${fixture.plist}`,
      ])
    }))

  it('a missing plist fails closed before any service command', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create({ operatingSystem: 'Darwin' })
      assertFailed(fixture.run('stop'), 'managed launch agent definition is missing')
      assert.deepEqual(fixture.calls(), [])
    }))

  it('an agent not owned by the installer fails closed before any service command', () =>
    withUpdateFixtures(({ create }) => {
      const fixture = create({ operatingSystem: 'Darwin' })
      fixture.writeServiceDefinition("<!-- someone else's agent -->")
      assertFailed(fixture.run('stop'), 'launch agent is not owned by the installer')
      assert.deepEqual(fixture.calls(), [])
    }))

  it('the stop wait times out when the agent stays loaded', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin', serviceMode: 'stop-stuck' })
      assertFailed(fixture.run('stop'), 'managed launch agent did not stop')
      const calls = fixture.calls()
      assert.ok(calls.filter((call) => call.startsWith('launchctl print ')).length > 1, calls.join('\n'))
      assertStoppedWithoutStarting(calls)
    }))

  it('a start failure is reported and never becomes a stop', () =>
    withUpdateFixtures(({ prepared }) => {
      const fixture = prepared({ operatingSystem: 'Darwin', serviceMode: 'start-fail' })
      assertFailed(fixture.run('start', { active: false }), 'could not restart the managed launch agent')
      assert.deepEqual(fixture.calls(), [
        `launchctl bootstrap ${fixture.domain} ${fixture.plist}`,
      ])
    }))
})
