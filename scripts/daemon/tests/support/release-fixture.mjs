/**
 * Local fixtures for Go daemon release archives and Unix installation tests. No fixture contacts
 * the network or registers a real service: curl and service managers are replaced by local shims.
 */

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { repositoryRoot } from '../../../dev/lib/repo-root.mjs'
import { treeEntries, writeTarGz } from './tar.mjs'

export const DAEMON_VERSION = '1.1.1'
export const RELEASE_TAG = `v${DAEMON_VERSION}`
export const RELEASE_BASE = 'https://github.com/fengwk/kk-studio/releases'
// TEST_BASH lets an isolated Bash 3.2 build run the same suite. Production and CI keep /bin/bash.
export const SYSTEM_BASH = process.env.TEST_BASH || '/bin/bash'

export const REPOSITORY_ROOT = repositoryRoot()
const TESTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon/tests')
const PTY_RUN = path.join(TESTS_DIR, 'support/pty_run.py')
const SCRIPTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon')

export const ENVIRONMENT_ID = '11111111-1111-4111-8111-111111111111'
export const SECOND_ENVIRONMENT_ID = '22222222-2222-4222-8222-222222222222'
export const ENVIRONMENT_VERSION = '0'

/** The current daemon.json envelope: a bare object with the card UUID, config version and daemonConfig. */
export function daemonJson({ environmentId = ENVIRONMENT_ID, version = ENVIRONMENT_VERSION, studioUrl = 'https://studio.example.com' } = {}) {
  return JSON.stringify({ environmentId, version, daemonConfig: { studioUrl } })
}

export function writeExecutable(file, content) {
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, content)
  chmodSync(file, 0o755)
}

export function writeText(file, content, mode = 0o600) {
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, content)
  chmodSync(file, mode)
}

export const sha256Of = (file) => createHash('sha256').update(readFileSync(file)).digest('hex')

const CURL_SHIM = `#!${process.execPath}
const fs = require('node:fs')
const path = require('node:path')

const args = process.argv.slice(2)
if (process.env.FAKE_CURL_LOG) {
  fs.appendFileSync(process.env.FAKE_CURL_LOG, JSON.stringify(args) + '\\n')
}
let url = null
let output = null
let writeOut = false
for (let i = 0; i < args.length; i += 1) {
  const arg = args[i]
  if (arg === '--output') {
    i += 1
    output = args[i]
  } else if (arg === '--write-out') {
    i += 1
    writeOut = true
  } else if (arg.startsWith('--output=')) {
    output = arg.slice('--output='.length)
  } else if (!arg.startsWith('-')) {
    url = arg
  }
}

const base = process.env.FAKE_RELEASE_BASE
if (url === null) {
  process.exit(22)
}
if (url === base + '/latest') {
  process.stdout.write(base + '/tag/' + process.env.FAKE_TAG)
  process.exit(0)
}
if (writeOut) {
  process.exit(22)
}
const source = path.join(process.env.FAKE_HTTP_DIR, url.split('/').pop())
if (!fs.existsSync(source) || !fs.statSync(source).isFile() || output === null) {
  process.stderr.write('fake curl: no fixture for ' + url + '\\n')
  process.exit(22)
}
fs.copyFileSync(source, output)
`

const asResult = (completed) => ({ status: completed.status, stdout: completed.stdout, stderr: completed.stderr })

/**
 * A fake host for `scripts/daemon/install.sh`.
 *
 * The installer still performs its real ownership/permission checks, SHA256 verification,
 * `--check-config` preflight and atomic program publication; only the network and service managers
 * are local shims, so no real service is registered or touched.
 */
export class InstallFixture {
  constructor(root, { hostOs = 'Linux', machine = 'x86_64' } = {}) {
    this.root = root
    this.script = path.join(SCRIPTS_DIR, 'install.sh')
    this.hostOs = hostOs
    this.machine = machine
    this.bindir = path.join(root, 'bin')
    this.httpDir = path.join(root, 'http')
    this.home = path.join(root, 'home')
    this.state = path.join(root, 'state')
    for (const directory of [this.bindir, this.httpDir, this.home, this.state]) {
      mkdirSync(directory, { recursive: true })
    }

    writeExecutable(path.join(this.bindir, 'curl'), CURL_SHIM)
    writeExecutable(path.join(this.bindir, 'uname'), this.unameShim())
    if (hostOs === 'Linux') {
      writeExecutable(path.join(this.bindir, 'systemctl'), SYSTEMCTL_SHIM)
      writeExecutable(path.join(this.bindir, 'journalctl'), `#!/bin/sh
case "$*" in
  *"--show-cursor"*)
    [ "$FAKE_NO_CURSOR" = 1 ] && exit 0
    printf '%s\\n' '-- cursor: fixture-cursor' ;;
  *"--after-cursor fixture-cursor"*)
    printf '%s\\n' "$FAKE_CURRENT_JOURNAL"
    [ "$FAKE_JOURNAL_FAIL" = 1 ] && exit 1 ;;
  *) printf '%s\\n' 'daemon failed: environment registration is rejected: invalid registration token old-secret' ;;
esac
exit 0
`)
    } else {
      writeExecutable(path.join(this.bindir, 'launchctl'), LAUNCHCTL_SHIM)
      writeExecutable(path.join(this.bindir, 'plutil'), PLUTIL_SHIM)
    }

    this.asset = `kk-studio-daemon-${RELEASE_TAG}-${this.releaseOs}-${this.releaseArch}.tar.gz`
    const archive = path.join(this.httpDir, this.asset)
    this.buildArchive(archive)
    writeText(path.join(this.httpDir, `${this.asset}.sha256`), `${sha256Of(archive)}  ${this.asset}\n`, 0o644)
  }

  get releaseOs() {
    return this.hostOs === 'Linux' ? 'linux' : 'macos'
  }

  get releaseArch() {
    return ['x86_64', 'amd64'].includes(this.machine) ? 'amd64' : 'arm64'
  }

  buildArchive(file, platform = null, version = DAEMON_VERSION, binaryPath = null) {
    const key = platform ?? `${this.releaseOs}-${this.releaseArch}`
    const binary = key.startsWith('windows-') ? 'kk-studio-daemon.exe' : 'kk-studio-daemon'
    const work = mkdtempSync(path.join(this.root, 'archive-src-'))
    try {
      const fakeDaemon = `#!/bin/sh
case "$1" in
  --version) printf 'kk-studio-daemon %s\\n' "\${FAKE_DAEMON_VERSION:-${version}}" ;;
  --check-config)
    if grep -Eq '"invalid"[[:space:]]*:[[:space:]]*true|"studioUrl"[[:space:]]*:[[:space:]]*"invalid"' "$2"; then printf 'Invalid daemon configuration: invalid test config\\n' >&2; exit 1; fi
    printf 'Daemon configuration is valid\\n' ;;
  __lock-helper)
    case "$2" in
      hold)
        if [ "$FAKE_DAEMON_BUSY" = 1 ]; then exit 75; fi
        printf 'locked\\n'; cat >/dev/null ;;
      wait) [ "$FAKE_DAEMON_LOCKED" = 1 ] && exit 76; exit 0 ;;
      *) exit 2 ;;
    esac ;;
  *) exit 1 ;;
esac
`
      writeExecutable(path.join(work, binary), binaryPath ? readFileSync(binaryPath) : fakeDaemon)
      writeText(path.join(work, 'runtime/code-server/out/node/entry.js'), '// bundled IDE runtime\\n', 0o644)
      writeText(path.join(work, 'ide-extension/extension.cjs'), '// IDE extension\\n', 0o644)
      writeText(path.join(work, 'runtime-manifest.json'), `{"daemonVersion":"${version}","codeServer":{"${key}":{}}}\n`, 0o644)
      writeText(path.join(work, 'LICENSE'), 'Apache License\\n', 0o644)
      writeText(path.join(work, 'THIRD_PARTY_NOTICES'), 'Go daemon notices\\n', 0o644)
      const entries = treeEntries(work, '.').slice(1).map((entry) => ({ ...entry, name: entry.name.replace(/^\.\//, '') }))
      writeTarGz(file, entries)
    } finally {
      rmSync(work, { recursive: true, force: true })
    }
  }

  unameShim() {
    return `#!/bin/sh
case "$1" in
  -s) printf '${this.hostOs}\\n' ;;
  -m) printf '${this.machine}\\n' ;;
  *) uname "$@" ;;
esac
`
  }

  makeInputs({ environmentId = ENVIRONMENT_ID, version = ENVIRONMENT_VERSION, config = null, token = 'token-value' } = {}) {
    const directory = path.join(this.root, 'inputs')
    mkdirSync(directory, { recursive: true, mode: 0o700 })
    const configFile = path.join(directory, 'daemon.json')
    const tokenFile = path.join(directory, 'daemon.token')
    writeText(configFile, config ?? daemonJson({ environmentId, version }), 0o600)
    writeText(tokenFile, token, 0o600)
    // 预检必须看到即将发布的凭据；fixture 检查器按此断言同目录 daemon.token 的字节。
    this.expectedToken = token
    return [configFile, tokenFile]
  }

  run(args, { extraEnv = {}, stderrTTY = false } = {}) {
    mkdirSync(path.join(this.root, 'tmp'), { recursive: true, mode: 0o700 })
    const env = {
      ...process.env,
      PATH: `${this.bindir}:${process.env.PATH}`,
      HOME: this.home,
      FAKE_RELEASE_BASE: RELEASE_BASE,
      FAKE_TAG: RELEASE_TAG,
      FAKE_HTTP_DIR: this.httpDir,
      FAKE_SERVICE_STATE: this.state,
      FAKE_EXPECTED_TOKEN: this.expectedToken,
      FAKE_KICKSTART_PID: String(process.pid),
      DAEMON_VERIFY_TIMEOUT_SECONDS: '1',
      DAEMON_VERIFY_STABLE_SECONDS: '0',
      TMPDIR: path.join(this.root, 'tmp'),
      ...extraEnv,
    }
    if (stderrTTY) {
      // stdin is /dev/null while stderr is a real PTY, even on a noninteractive macOS runner.
      return asResult(spawnSync('python3', [PTY_RUN, SYSTEM_BASH, this.script, ...args], {
        cwd: this.root, env, encoding: 'utf8',
      }))
    }
    return asResult(spawnSync(SYSTEM_BASH, [this.script, ...args], { cwd: this.root, env, encoding: 'utf8' }))
  }

  get installRoot() {
    return path.join(this.home, '.kk-studio')
  }

  get envRoot() {
    return path.join(this.installRoot, 'environments', this.environmentId ?? ENVIRONMENT_ID)
  }

  get unitPath() {
    return this.hostOs === 'Linux'
      ? path.join(this.home, `.config/systemd/user/kk-studio-daemon-${this.environmentId ?? ENVIRONMENT_ID}.service`)
      : path.join(this.home, `Library/LaunchAgents/fun.fengwk.kkstudio.environment-daemon.${this.environmentId ?? ENVIRONMENT_ID}.plist`)
  }
}

const SYSTEMCTL_SHIM = String.raw`#!/bin/sh
state="$FAKE_SERVICE_STATE"
printf '%s\n' "$*" >> "$state/commands"
if [ -n "$FAKE_MANAGER_FAIL" ] && [ "$2" = "$FAKE_MANAGER_FAIL" ]; then
  echo 'injected-manager-secret' >&2
  exit 1
fi
case "$*" in
  *"reset-failed"*)
    [ "$FAKE_RESET_FAIL" = 1 ] && exit 1
    rm -f "$state/start-limit"
    exit 0 ;;
  *"show-environment"*) exit 0 ;;
  *"show --property=FragmentPath"*)
    if [ -f "$state/unit.path" ]; then cat "$state/unit.path"; fi
    exit 0
    ;;
  *"is-active --quiet"*)
    [ "$FAKE_INACTIVE" = 1 ] && exit 3
    [ -f "$state/active" ] && exit 0 || exit 3
    ;;
  *"restart"* | *"start"*)
    [ -f "$state/start-limit" ] && exit 1
    [ "$FAKE_START_FAIL" = 1 ] && exit 1
    : > "$state/active"
    exit 0
    ;;
  *"stop"* | *"disable"*)
    rm -f "$state/active"
    exit 0
    ;;
  *"status"*)
    [ -f "$state/active" ] && exit 0 || exit 3
    ;;
  *) exit 0 ;;
esac
`

const LAUNCHCTL_SHIM = String.raw`#!/bin/sh
state="$FAKE_SERVICE_STATE"
case "$1" in
  print)
    last=""
    for target in "$@"; do last="$target"; done
    case "$last" in
      gui/*/*) [ -f "$state/active" ] && exit 0 || exit 1 ;;
      gui/*) exit 0 ;;
      *) exit 0 ;;
    esac
    ;;
  bootout)
    rm -f "$state/active"
    exit 0
    ;;
  bootstrap)
    if [ "$FAKE_MANAGER_FAIL" = bootstrap ]; then
      echo 'injected-manager-secret' >&2
      exit 1
    fi
    : > "$state/active"
    exit 0
    ;;
  kickstart)
    printf '%s\n' "$FAKE_KICKSTART_PID"
    exit 0
    ;;
  *) exit 0 ;;
esac
`

const PLUTIL_SHIM = String.raw`#!/bin/sh
# plutil -lint <plist>: accept well-formed enough plists, reject garbage.
if [ "$1" = "-lint" ]; then
  if head -n 1 "$2" | grep -q '^<'; then exit 0; fi
  exit 1
fi
exit 0
`

/** Run `body(tmp)` with a fresh temporary directory that is removed once `body` (sync or async) settles. */
export function withTempDir(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'kk-daemon-'))
  const cleanup = () => rmSync(directory, { recursive: true, force: true })
  let result
  try {
    result = body(directory)
  } catch (error) {
    cleanup()
    throw error
  }
  if (typeof result?.then === 'function') {
    return result.finally(cleanup)
  }
  cleanup()
  return result
}
