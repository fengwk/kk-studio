/**
 * Disposable operation directory plus stubbed OS service tooling for the managed UNIX update script.
 *
 * The update script runs as an independent short-lived OS job after the daemon accepted the
 * handoff. The fixture provides a disposable HOME, a temporary operation directory and stubbed
 * `uname`/`systemctl`/`launchctl`/`mv`/`mktemp`/`sleep` tools, so no real service is ever
 * registered, stopped or restarted and no path outside the fixture is touched.
 */

import { spawn, spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { SYSTEM_BASH, nodeExport } from './release-fixture.mjs'

export const UNIT_MARKER = '# Managed by scripts/daemon/install.sh'
export const PLIST_MARKER = '<!-- Managed by scripts/daemon/install.sh -->'
export const SERVICE_NAME = 'kk-studio-daemon.service'
export const LAUNCHD_LABEL = 'fun.fengwk.kkstudio.environment-daemon'

export const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis))

function writeTool(file, body) {
  writeFileSync(file, `#!/bin/sh\n${body}`)
  chmodSync(file, 0o755)
}

export class UpdateFixture {
  constructor(
    root,
    {
      operatingSystem = 'Linux',
      serviceMode = 'ok',
      mvMode = 'ok',
      mktempMode = 'ok',
      handoffState = 'ACCEPTED',
      handoffOperation = null,
      permit = 'match',
      fastSleep = false,
      unameMode = 'ok',
      resultMode = 'ok',
    } = {},
  ) {
    this.root = realpathSync(root)
    this.home = path.join(this.root, 'home')
    this.bin = path.join(this.root, 'bin')
    this.operationId = '11111111-1111-1111-1111-111111111111'
    this.updateDir = path.join(this.root, 'updates', this.operationId)
    this.staged = path.join(this.updateDir, 'extract')
    this.installed = path.join(this.root, 'lib')
    this.handoff = path.join(this.updateDir, 'handoff.json')
    this.permit = path.join(this.updateDir, 'permit')
    this.result = path.join(this.updateDir, 'update-result.json')
    this.record = path.join(this.root, 'calls.txt')
    this.script = path.join(this.root, 'kk-studio-daemon-update.sh')
    this.operatingSystem = operatingSystem
    this.serviceMode = serviceMode
    this.mvMode = mvMode
    this.mktempMode = mktempMode
    this.handoffState = handoffState
    this.handoffOperation = handoffOperation
    this.permitMode = permit
    this.unameMode = unameMode
    this.resultMode = resultMode
    this.unit = path.join(this.home, '.config/systemd/user', SERVICE_NAME)
    this.plist = path.join(this.home, 'Library/LaunchAgents', `${LAUNCHD_LABEL}.plist`)
    this.domain = `gui/${process.getuid()}`
    this.build(fastSleep)
  }

  build(fastSleep) {
    for (const directory of [this.home, this.bin, this.updateDir, this.staged, this.installed]) {
      mkdirSync(directory, { recursive: true })
    }
    writeFileSync(this.record, '')
    writeFileSync(this.script, nodeExport('UNIX_UPDATE_SCRIPT'))
    chmodSync(this.script, 0o755)
    if (this.unameMode === 'fail') {
      // An unguarded `os=$(uname -s)` failing under `set -e` aborts outside fail_closed.
      writeTool(path.join(this.bin, 'uname'), 'exit 1\n')
    } else {
      writeTool(path.join(this.bin, 'uname'), 'echo "${FAKE_UNAME:-Linux}"\n')
    }
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
exit 0
`,
    )
    writeTool(
      path.join(this.bin, 'launchctl'),
      `echo "launchctl $*" >> "$FAKE_RECORD"
state="$FAKE_SERVICE_STATE"
case "$1" in
  print) [ -f "$state/active" ] && exit 0 || exit 1 ;;
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
exit 0
`,
    )
    if (this.mvMode !== 'ok') {
      writeTool(
        path.join(this.bin, 'mv'),
        `echo "mv $*" >> "$FAKE_RECORD"
last=
for argument in "$@"; do last=$argument; done
if [ "${this.mvMode}" = replace-fail ] && [ "$last" = "$FAKE_INSTALLED" ]; then exit 1; fi
if [ "${this.mvMode}" = backup-fail ] && [ "$(basename "$last")" = previous ]; then exit 1; fi
exec /bin/mv "$@"
`,
      )
    }
    if (this.mktempMode === 'fail') {
      writeTool(path.join(this.bin, 'mktemp'), 'exit 1\n')
    }
    if (fastSleep) {
      // The production waits are 120s/30s. A no-op sleep exercises the bounded loops without
      // patching the real script text or sleeping in the suite.
      writeTool(path.join(this.bin, 'sleep'), 'exit 0\n')
    }
    // Placeholder distributions: the update script moves directories, never reads bytes.
    writeFileSync(path.join(this.staged, 'main.js'), 'staged-release-main-js\n')
    writeFileSync(path.join(this.installed, 'main.js'), 'installed-release-main-js\n')
    if (this.resultMode === 'unwritable') {
      // A directory at the receipt path makes every receipt write fail.
      mkdirSync(this.result)
    }
    if (this.handoffState !== 'missing') {
      this.writeHandoff(this.handoffState)
    }
    if (this.permitMode === 'match') {
      writeFileSync(this.permit, `${this.operationId}\n`)
    } else if (this.permitMode === 'directory') {
      mkdirSync(this.permit)
    } else if (this.permitMode === 'symlink') {
      const target = path.join(this.root, 'foreign-permit')
      writeFileSync(target, `${this.operationId}\n`)
      symlinkSync(target, this.permit)
    }
  }

  /** (Re)write the handoff metadata, e.g. to simulate PREPARING -> ACCEPTED. */
  writeHandoff(state, operation = null) {
    writeFileSync(
      this.handoff,
      JSON.stringify({
        state,
        operationId: operation ?? this.handoffOperation ?? this.operationId,
        targetVersion: '1.1.1',
      }),
    )
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

  environment() {
    return {
      HOME: this.home,
      PATH: `${this.bin}:/usr/bin:/bin`,
      FAKE_RECORD: this.record,
      FAKE_MODE: this.serviceMode,
      FAKE_UNAME: this.operatingSystem,
      FAKE_INSTALLED: this.installed,
      FAKE_SERVICE_STATE: path.join(this.root, 'service-state'),
    }
  }

  command() {
    return [this.script, this.updateDir, this.staged, this.installed]
  }

  markServiceActive() {
    mkdirSync(path.join(this.root, 'service-state'), { recursive: true })
    writeFileSync(path.join(this.root, 'service-state', 'active'), '')
  }

  run() {
    this.markServiceActive()
    const result = spawnSync(SYSTEM_BASH, this.command(), {
      cwd: this.root,
      env: this.environment(),
      encoding: 'utf8',
      timeout: 60_000,
    })
    return { status: result.status, stdout: result.stdout, stderr: result.stderr }
  }

  /** Start the script without waiting; resolves `finished` with its result when it exits. */
  start() {
    this.markServiceActive()
    const child = spawn(SYSTEM_BASH, this.command(), { cwd: this.root, env: this.environment() })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => {
      stdout += chunk
    })
    child.stderr.on('data', (chunk) => {
      stderr += chunk
    })
    const finished = new Promise((resolve) => child.once('close', (status) => resolve({ status, stdout, stderr })))
    return { child, finished }
  }

  resultDocument() {
    return JSON.parse(readFileSync(this.result, 'utf8'))
  }

  calls() {
    return readFileSync(this.record, 'utf8').split('\n').filter(Boolean)
  }

  backupDirectories() {
    return readdirSync(this.updateDir)
      .filter((name) => name.startsWith('backup.'))
      .sort()
      .map((name) => path.join(this.updateDir, name))
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
