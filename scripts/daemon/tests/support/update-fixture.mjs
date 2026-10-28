/**
 * Disposable paths and stubbed OS service tooling for the managed UNIX update script.
 *
 * The shipped script only launches the independent updater (`run`) or stops/starts the
 * installer-owned user service. Handoff, permit, replacement and result state belong to the Node
 * updater and are covered by daemon/test/update-entry.test.ts; this fixture deliberately does not
 * reproduce that state machine.
 */

import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { SYSTEM_BASH, nodeExport } from './release-fixture.mjs'

export const UNIT_MARKER = '# Managed by scripts/daemon/install.sh'
export const PLIST_MARKER = '<!-- Managed by scripts/daemon/install.sh -->'
export const SERVICE_NAME = 'kk-studio-daemon.service'
export const LAUNCHD_LABEL = 'fun.fengwk.kkstudio.environment-daemon'

function writeTool(file, body) {
  writeFileSync(file, `#!/bin/sh\n${body}`)
  chmodSync(file, 0o755)
}

export class UpdateFixture {
  constructor(root, { operatingSystem = 'Linux', serviceMode = 'ok' } = {}) {
    this.root = realpathSync(root)
    this.home = path.join(this.root, 'home')
    this.bin = path.join(this.root, 'bin')
    this.operationId = '11111111-1111-1111-1111-111111111111'
    this.updateDir = path.join(this.root, 'updates', this.operationId)
    this.staged = path.join(this.updateDir, 'extract')
    this.installed = path.join(this.root, 'lib')
    this.record = path.join(this.root, 'calls.txt')
    this.script = path.join(this.root, 'kk-studio-daemon-update.sh')
    this.updaterNode = path.join(this.root, 'updater-node')
    this.updaterEntry = path.join(this.updateDir, 'updater-entry.js')
    this.operatingSystem = operatingSystem
    this.serviceMode = serviceMode
    this.stateDir = path.join(this.root, 'service-state')
    this.unit = path.join(this.home, '.config/systemd/user', SERVICE_NAME)
    this.plist = path.join(this.home, 'Library/LaunchAgents', `${LAUNCHD_LABEL}.plist`)
    this.domain = `gui/${process.getuid()}`
    this.build()
  }

  build() {
    for (const directory of [this.home, this.bin, this.updateDir, this.staged, this.installed, this.stateDir]) {
      mkdirSync(directory, { recursive: true })
    }
    writeFileSync(this.record, '')
    writeFileSync(this.script, nodeExport('UNIX_UPDATE_SCRIPT'))
    chmodSync(this.script, 0o755)
    writeFileSync(this.updaterEntry, '// independent updater entry\n')

    // The run action execs the private updater; the stub records its exact argv and propagates a
    // controlled exit code so the script's `exec` contract is observable without a real update.
    writeTool(
      this.updaterNode,
      `printf 'updater' >> "$FAKE_RECORD"
for argument in "$@"; do printf '|%s' "$argument" >> "$FAKE_RECORD"; done
printf '\\n' >> "$FAKE_RECORD"
exit "\${FAKE_UPDATER_EXIT:-0}"
`,
    )
    writeTool(path.join(this.bin, 'uname'), 'echo "${FAKE_UNAME:-Linux}"\n')
    writeTool(
      path.join(this.bin, 'systemctl'),
      `echo "systemctl $*" >> "$FAKE_RECORD"
state="$FAKE_SERVICE_STATE"
case " $* " in
  *" is-active "*)
    [ -f "$state/active" ] && exit 0
    exit 1
    ;;
  *" stop "*)
    [ "$FAKE_MODE" = stop-fail ] && exit 1
    [ "$FAKE_MODE" = stop-stuck ] && exit 0
    rm -f "$state/active"
    exit 0
    ;;
  *" start "*)
    [ "$FAKE_MODE" = start-fail ] && exit 1
    : > "$state/active"
    exit 0
    ;;
esac
exit 1
`,
    )
    writeTool(
      path.join(this.bin, 'launchctl'),
      `echo "launchctl $*" >> "$FAKE_RECORD"
state="$FAKE_SERVICE_STATE"
case "$1" in
  print)
    [ -f "$state/active" ] && exit 0
    exit 1
    ;;
  bootout)
    [ "$FAKE_MODE" = stop-stuck ] && exit 0
    rm -f "$state/active"
    exit 0
    ;;
  bootstrap)
    [ "$FAKE_MODE" = start-fail ] && exit 1
    : > "$state/active"
    exit 0
    ;;
esac
exit 1
`,
    )
    // The script's production waits are bounded in seconds; a no-op sleep keeps timeout tests fast
    // while still exercising every loop iteration.
    writeTool(path.join(this.bin, 'sleep'), 'exit 0\n')
  }

  writeServiceDefinition(marker) {
    if (this.operatingSystem === 'Darwin') {
      mkdirSync(path.dirname(this.plist), { recursive: true })
      writeFileSync(this.plist, `<?xml version="1.0"?>\n${marker}\n`)
    } else {
      mkdirSync(path.dirname(this.unit), { recursive: true })
      writeFileSync(this.unit, `${marker}\nUnit fixture\n`)
    }
  }

  environment(overrides = {}) {
    return {
      HOME: this.home,
      PATH: `${this.bin}:/usr/bin:/bin`,
      FAKE_RECORD: this.record,
      FAKE_MODE: this.serviceMode,
      FAKE_UNAME: this.operatingSystem,
      FAKE_SERVICE_STATE: this.stateDir,
      ...overrides,
    }
  }

  command(action) {
    if (action === 'run') {
      return [
        this.script,
        'run',
        this.updateDir,
        this.staged,
        this.installed,
        this.updaterNode,
        this.updaterEntry,
      ]
    }
    return [this.script, action, this.updateDir, this.staged, this.installed]
  }

  markServiceActive() {
    writeFileSync(path.join(this.stateDir, 'active'), '')
  }

  clearServiceActive() {
    rmSync(path.join(this.stateDir, 'active'), { force: true })
  }

  invoke(args, { active = false, env = {} } = {}) {
    if (active) {
      this.markServiceActive()
    } else {
      this.clearServiceActive()
    }
    const result = spawnSync(SYSTEM_BASH, args, {
      cwd: this.root,
      env: this.environment(env),
      encoding: 'utf8',
      timeout: 60_000,
    })
    return { status: result.status, stdout: result.stdout, stderr: result.stderr }
  }

  run(action, options = {}) {
    return this.invoke(this.command(action), { active: action === 'stop', ...options })
  }

  calls() {
    return readFileSync(this.record, 'utf8').split('\n').filter(Boolean)
  }
}

/** Run `body(fixtureFactory)` where every fixture lives in a fresh temporary directory removed afterwards. */
export async function withUpdateFixtures(body) {
  const directories = []
  const create = (overrides = {}) => {
    const directory = mkdtempSync(path.join(tmpdir(), 'kk-update-'))
    directories.push(directory)
    return new UpdateFixture(directory, overrides)
  }
  const prepared = (overrides = {}) => {
    const fixture = create(overrides)
    fixture.writeServiceDefinition(fixture.operatingSystem === 'Darwin' ? PLIST_MARKER : UNIT_MARKER)
    return fixture
  }
  try {
    return await body({ create, prepared })
  } finally {
    for (const directory of directories) {
      rmSync(directory, { recursive: true, force: true })
    }
  }
}
