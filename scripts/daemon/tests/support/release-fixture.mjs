/**
 * Shared fixtures for the Node Daemon packaging, installation and update tests.
 *
 * These fixtures never touch the network, never register a real service and never download the
 * real code-server runtime. They build a tiny, self-contained fake repository plus PATH shims
 * (curl / npm / uname / service managers) that are behaviourally faithful to the contracts the
 * scripts rely on:
 *
 * - `curl` maps the official URLs onto local fixture files.
 * - `npm` honours `--os` / `--cpu` by materialising only the matching `@koromix/koffi-<os>-<cpu>`
 *   optional prebuilt, and refuses any install that would run target lifecycle scripts without
 *   `--ignore-scripts`.
 * - the daemon entrypoint and the frozen `lib/node` are real executables with a controlled
 *   `--version` / `--check-config` contract.
 * - the managed update script is imported from the shipped TypeScript module, so the tests never
 *   keep a second copy of the UNIX/Windows update facts.
 */

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

import { repositoryRoot } from '../../../dev/lib/repo-root.mjs'
import { treeEntries, writeTarGz } from './tar.mjs'

export const DAEMON_VERSION = '1.1.1'
export const CODE_SERVER_VERSION = '4.141.0-kk.1'
export const RELEASE_TAG = `v${DAEMON_VERSION}`
export const RELEASE_BASE = 'https://github.com/fengwk/kk-studio/releases'
export const PLATFORMS = ['linux-amd64', 'linux-arm64', 'macos-amd64', 'macos-arm64', 'windows-amd64']
// TEST_BASH lets an isolated Bash 3.2 build run the same suite. Production and CI keep /bin/bash.
export const SYSTEM_BASH = process.env.TEST_BASH || '/bin/bash'

export const REPOSITORY_ROOT = repositoryRoot()
const TESTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon/tests')
const PTY_RUN = path.join(TESTS_DIR, 'support/pty_run.py')
export const UPDATE_SCRIPTS_TS = path.join(REPOSITORY_ROOT, 'daemon/src/update-scripts.ts')
export const EXPORT_UPDATE_SCRIPT_MJS = path.join(TESTS_DIR, 'export-update-script.mjs')
const SCRIPTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon')

const MAIN_JS = `console.log("kk-studio-daemon ${DAEMON_VERSION}")\n`

// The frozen runtime's lib/node is invoked as `lib/node -e ...` or
// `lib/node <main.js> --version|--check-config <cfg>`.
const FAKE_NODE = `#!/bin/sh
# Frozen runtime Node shim used only by tests.
if [ "$1" = -e ]; then
  # 安装器调用是 \`node -e <script> <manifest> <platform>\`。
  if grep -q "\\"$4\\"" "$3"; then printf '%s' "$4"; exit 0; fi
  exit 2
fi
case "$2" in
  --version) printf 'kk-studio-daemon %s\\n' "\${FAKE_NODE_VERSION:-${DAEMON_VERSION}}" ;;
  --check-config)
    if [ ! -s "$3" ]; then exit 2; fi
    # 真实 daemon.json 是裸配置对象；这里同样拒绝非法取值与外层 "daemon" 包裹的假结构，
    # 以免 fixture 接受真实入口会拒绝的配置而假绿。
    if grep -q '"invalid"' "$3" || grep -q '"daemon"[[:space:]]*:' "$3"; then
      printf 'Invalid daemon configuration: daemon.daemon: unknown field\\n' >&2
      exit 2
    fi
    printf 'Daemon configuration is valid\\n'
    ;;
  *) exit 3 ;;
esac
`

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

/**
 * Import one constant from the shipped `update-scripts.ts` through Node.
 *
 * The module has no imports and contains only exported template strings, so Node can load it
 * directly with type stripping. Requiring a Java build or `dist` here would let the tests drift
 * from the real source. The export travels as a UTF-8 file produced by the same exporter the
 * PowerShell fixtures use: Windows PowerShell 5.1 corrupts an inline `-e` script and decodes native
 * stdout with the console code page, so neither command-line source nor stdout is used.
 */
export function nodeExport(name) {
  const work = mkdtempSync(path.join(tmpdir(), 'kk-update-export-'))
  try {
    const exported = path.join(work, 'update-script.txt')
    const result = spawnSync(process.execPath, [EXPORT_UPDATE_SCRIPT_MJS, UPDATE_SCRIPTS_TS, name, exported], {
      encoding: 'utf8',
    })
    if (result.status !== 0) {
      throw new Error(`cannot import ${name} from ${UPDATE_SCRIPTS_TS}: exit ${result.status}: ${result.stderr}`)
    }
    // Decoded byte for byte as UTF-8: this is the production script text itself.
    return readFileSync(exported, 'utf8')
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

/** Build a minimal stand-in for one frozen fork code-server archive. */
export function codeServerTar(file, platform) {
  const work = mkdtempSync(path.join(path.dirname(file), `cs-src-${platform}-`))
  try {
    const nodeName = platform.startsWith('windows-') ? 'node.exe' : 'node'
    const rootName = `code-server-${CODE_SERVER_VERSION}-${platform}`
    const root = path.join(work, rootName)
    writeExecutable(path.join(root, 'lib', nodeName), FAKE_NODE)
    writeText(path.join(root, 'out/node/entry.js'), '// frozen entry\n', 0o644)
    mkdirSync(path.join(root, 'bin'))
    symlinkSync(path.join('..', 'out', 'node', 'entry.js'), path.join(root, 'bin', 'code-server'))
    writeTarGz(file, treeEntries(root, rootName))
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

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

function npmShim() {
  // The archive validator uses the real parser shipped with the daemon's own dependencies.
  const parser = path.join(REPOSITORY_ROOT, 'daemon/node_modules/tar')
  return `#!${process.execPath}
const fs = require('node:fs')
const path = require('node:path')
const TAR_PARSER = ${JSON.stringify(parser)}

const args = process.argv.slice(2)
let prefix = null
let targetOs = null
let targetCpu = null
let ignoreScripts = false
for (let i = 0; i < args.length; i += 1) {
  const arg = args[i]
  if (arg === '--prefix') {
    i += 1
    prefix = args[i]
  } else if (arg.startsWith('--prefix=')) {
    prefix = arg.slice('--prefix='.length)
  } else if (arg.startsWith('--os=')) {
    targetOs = arg.slice('--os='.length)
  } else if (arg.startsWith('--cpu=')) {
    targetCpu = arg.slice('--cpu='.length)
  } else if (arg === '--ignore-scripts') {
    ignoreScripts = true
  }
}

if (process.env.FAKE_NPM_LOG) {
  fs.appendFileSync(process.env.FAKE_NPM_LOG, JSON.stringify(args) + '\\n')
}

if (prefix === null) {
  process.exit(1)
}
// Real npm runs the target package's lifecycle scripts here. koffi's install script loads the
// target prebuilt in the host process and falls back to a source build; cross-platform staging
// only ever ships the locked prebuilt, so an install without --ignore-scripts must fail.
if (!ignoreScripts) {
  process.stderr.write('fake npm: refusing to run target lifecycle scripts without --ignore-scripts\\n')
  process.stderr.write(
    'fake npm: koffi install script cannot load @koromix/koffi-' + targetOs + '-' + targetCpu +
      ' on this host and failed to rebuild from source\\n',
  )
  process.exit(1)
}
fs.mkdirSync(path.join(prefix, 'node_modules'), { recursive: true })
fs.symlinkSync(TAR_PARSER, path.join(prefix, 'node_modules', 'tar'), 'dir')
if (process.env.FAKE_NPM_OMIT_PREBUILT === '1' || targetOs === null || targetCpu === null) {
  process.exit(0)
}
const prebuilt = path.join(prefix, 'node_modules', '@koromix', 'koffi-' + targetOs + '-' + targetCpu)
fs.mkdirSync(prebuilt, { recursive: true })
fs.writeFileSync(path.join(prebuilt, 'index.js'), 'module.exports = {}\\n')
if (process.env.FAKE_NPM_FOREIGN_PREBUILT === '1') {
  const foreign = path.join(prefix, 'node_modules', '@koromix', 'koffi-freebsd-x64')
  fs.mkdirSync(foreign, { recursive: true })
  fs.writeFileSync(path.join(foreign, 'index.js'), 'module.exports = {}\\n')
}
`
}

const asResult = (completed) => ({ status: completed.status, stdout: completed.stdout, stderr: completed.stderr })

/** A fake repository root for `scripts/daemon/prepare-release.sh`. */
export class ReleaseFixture {
  constructor(root) {
    this.root = root
    this.script = path.join(SCRIPTS_DIR, 'prepare-release.sh')
    this.bindir = path.join(root, 'bin')
    this.httpDir = path.join(root, 'http')
    this.repo = path.join(root, 'repo')
    // 每次 npm 调用的原始参数追加写入这里，供测试断言选择器与 --ignore-scripts。
    this.npmLog = path.join(root, 'npm-args.log')
    mkdirSync(this.bindir, { recursive: true })
    mkdirSync(this.httpDir, { recursive: true })

    writeExecutable(path.join(this.bindir, 'curl'), CURL_SHIM)
    writeExecutable(path.join(this.bindir, 'npm'), npmShim())

    const daemon = path.join(this.repo, 'daemon')
    writeText(path.join(this.repo, 'LICENSE'), 'Apache License 2.0\n', 0o644)
    writeText(path.join(daemon, 'THIRD_PARTY_NOTICES'), 'third-party notices\n', 0o644)
    writeText(path.join(daemon, 'package.json'), `{"name":"@kk-studio/daemon","version":"${DAEMON_VERSION}"}\n`, 0o644)
    writeText(path.join(daemon, 'package-lock.json'), '{}\n', 0o644)
    writeText(path.join(daemon, 'dist', 'main.js'), MAIN_JS, 0o644)
    writeExecutable(path.join(daemon, 'ide-extension', 'extension.js'), 'module.exports = {}\n')

    const codeServer = {}
    for (const platform of PLATFORMS) {
      const name = `code-server-${CODE_SERVER_VERSION}-${platform}.tar.gz`
      const runtimeTar = path.join(this.httpDir, name)
      codeServerTar(runtimeTar, platform)
      codeServer[platform] = {
        url: `https://github.com/fengwk/code-server/releases/download/v${CODE_SERVER_VERSION}/${name}`,
        sha256: sha256Of(runtimeTar),
      }
    }
    writeText(
      path.join(daemon, 'runtime-manifest.json'),
      JSON.stringify({ schemaVersion: 1, codeServerVersion: CODE_SERVER_VERSION, codeServer }, null, 2) + '\n',
      0o644,
    )
    this.gitInit()
  }

  gitInit() {
    const env = {
      ...process.env,
      GIT_AUTHOR_NAME: 'fixture',
      GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
      GIT_COMMITTER_NAME: 'fixture',
      GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
    }
    for (const command of [
      ['init', '-q', '-b', 'main'],
      ['add', '-A'],
      ['commit', '-q', '-m', 'fixture'],
    ]) {
      const result = spawnSync('git', command, { cwd: this.repo, env })
      if (result.status !== 0) {
        throw new Error(`git ${command[0]} failed: ${result.stderr}`)
      }
    }
  }

  run(args, extraEnv = {}) {
    const env = {
      ...process.env,
      PATH: `${this.bindir}:${process.env.PATH}`,
      KK_STUDIO_REPO_ROOT: this.repo,
      FAKE_RELEASE_BASE: RELEASE_BASE,
      FAKE_TAG: RELEASE_TAG,
      FAKE_HTTP_DIR: this.httpDir,
      FAKE_NPM_LOG: this.npmLog,
      NODE_BIN: process.execPath,
      NPM_BIN: path.join(this.bindir, 'npm'),
      ...extraEnv,
    }
    return asResult(spawnSync(SYSTEM_BASH, [this.script, ...args], { cwd: this.repo, env, encoding: 'utf8' }))
  }

  releaseDir(platform) {
    return path.join(this.repo, 'daemon', 'release', platform)
  }
}

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

  buildArchive(file, platform = null) {
    const key = platform ?? `${this.releaseOs}-${this.releaseArch}`
    const nodeName = key.startsWith('windows-') ? 'node.exe' : 'node'
    const work = mkdtempSync(path.join(this.root, 'archive-src-'))
    try {
      writeExecutable(path.join(work, 'runtime/code-server/lib', nodeName), FAKE_NODE)
      writeText(path.join(work, 'runtime/code-server/out/node/entry.js'), '// frozen entry\n', 0o644)
      writeText(path.join(work, 'main.js'), MAIN_JS, 0o644)
      writeText(
        path.join(work, 'runtime-manifest.json'),
        `{"schemaVersion":1,"daemonVersion":"${DAEMON_VERSION}","codeServer":{"${key}":{"url":"https://example.invalid"}}}\n`,
        0o644,
      )
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

  makeInputs({ config = '{"studioUrl":"https://studio.example.com"}', token = 'token-value' } = {}) {
    const directory = path.join(this.root, 'inputs')
    mkdirSync(directory, { recursive: true, mode: 0o700 })
    const configFile = path.join(directory, 'daemon.json')
    const tokenFile = path.join(directory, 'daemon.token')
    writeText(configFile, config, 0o600)
    writeText(tokenFile, token, 0o600)
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
