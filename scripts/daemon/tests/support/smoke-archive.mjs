/**
 * Fixtures for the `scripts/daemon/smoke-archive.{sh,ps1}` contract tests.
 *
 * Lightweight fake archives with a shimmed frozen runtime exercise every failure boundary of the
 * real smoke script; the shim only satisfies the harness's own call shapes and does not prove
 * product behaviour (the release pipeline's real archive smoke does).
 */

import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

import { DAEMON_VERSION, REPOSITORY_ROOT, writeExecutable } from './release-fixture.mjs'
import { treeEntries, writeTarGz } from './tar.mjs'

export const hostOs = () => ({ linux: 'linux', darwin: 'macos' })[process.platform] ?? process.platform
export const hostArch = () => ({ x64: 'amd64', arm64: 'arm64' })[process.arch] ?? process.arch
export const isUnixHost = () => ['linux', 'darwin'].includes(process.platform)

export const ARCHIVE_NAME = (arch) => `kk-studio-daemon-v${DAEMON_VERSION}-${hostOs()}-${arch}.tar.gz`

const SCRIPTS_DIR = path.join(REPOSITORY_ROOT, 'scripts/daemon')
export const SMOKE = path.join(SCRIPTS_DIR, 'smoke-archive.sh')
export const WINDOWS_SMOKE = path.join(SCRIPTS_DIR, 'smoke-archive.ps1')
const SHIM_TEMPLATE = path.join(SCRIPTS_DIR, 'tests/fixtures/fake-frozen-runtime.sh.in')

// Host npm/koffi naming: the archive must carry the prebuilt for this host's architecture.
const KOFFI = {
  'linux-amd64': 'koffi-linux-x64',
  'linux-arm64': 'koffi-linux-arm64',
  'macos-amd64': 'koffi-darwin-x64',
  'macos-arm64': 'koffi-darwin-arm64',
}

// Host npm/ripgrep naming: the archive must carry the rg binary for this host's architecture.
const RIPGREP = {
  'linux-amd64': 'ripgrep-linux-x64',
  'linux-arm64': 'ripgrep-linux-arm64',
  'macos-amd64': 'ripgrep-darwin-x64',
  'macos-arm64': 'ripgrep-darwin-arm64',
}

// The installed koffi prebuilt paths: the real-probe regression copies one loadable host native from
// here; CI runs `npm --prefix daemon ci` before these tests, so a missing dependency must fail.
const KOFFI_PREBUILT = {
  'linux-amd64': ['koffi-linux-x64', 'linux_x64'],
  'linux-arm64': ['koffi-linux-arm64', 'linux_arm64'],
  'macos-amd64': ['koffi-darwin-x64', 'darwin_x64'],
  'macos-arm64': ['koffi-darwin-arm64', 'darwin_arm64'],
}

/** One platform marker foreign to each host, used to prove the probe judges a stable root-relative path. */
export const FOREIGN_MARKER = {
  'linux-amd64': 'darwin-x64',
  'linux-arm64': 'darwin-arm64',
  'macos-amd64': 'linux-x64',
  'macos-arm64': 'linux-arm64',
}

export function hostNativeBytes() {
  const [pkg, libdir] = KOFFI_PREBUILT[`${hostOs()}-${hostArch()}`]
  const native = path.join(REPOSITORY_ROOT, 'daemon/node_modules/@koromix', pkg, libdir, 'koffi.node')
  try {
    return readFileSync(native)
  } catch {
    throw new Error(`missing loadable host native ${native}; run \`npm --prefix daemon ci\` first`)
  }
}

/** The smoke's native probe is a heredoc; extract exactly that block so the tests run the real JS. */
export function extractNativeProbe() {
  const match = /native_probe="\$tmp\/probe-code-server-native\.mjs"\ncat >"\$native_probe" <<'PROBE'\n([\s\S]*?)\nPROBE\n/.exec(
    readFileSync(SMOKE, 'utf8'),
  )
  if (match === null) {
    throw new Error('could not extract the native probe heredoc from smoke-archive.sh')
  }
  return `${match[1]}\n`
}

export const windowsSmoke = () => readFileSync(WINDOWS_SMOKE, 'utf8')

/** The Windows smoke embeds its native probe as a PowerShell here-string; extract exactly that block. */
export function extractWindowsNativeProbe() {
  const match = /\[System\.IO\.File\]::WriteAllText\(\$probe, @'\n([\s\S]*?)\n'@, \$utf8\)\n/.exec(windowsSmoke())
  if (match === null) {
    throw new Error('could not extract the native probe here-string from smoke-archive.ps1')
  }
  return `${match[1]}\n`
}

/**
 * Stub for the archive's Windows `native` module: only the call shapes the probe uses (private
 * dir/file creation and an exclusive directory lock), with no real Windows ACL semantics.
 */
export const WINDOWS_PROBE_NATIVE_STUB = `import { mkdirSync, writeFileSync } from 'node:fs'

export async function ensurePrivateDirectory(directory) {
  mkdirSync(directory, { recursive: true, mode: 0o700 })
}

export async function writePrivateFile(file, content) {
  writeFileSync(file, content, { mode: 0o600 })
}

let held = false

export async function acquireDirectoryLock() {
  if (held) {
    throw new Error('the directory is already locked')
  }
  held = true
  return { close: async () => { held = false } }
}
`

const absolute = (tool) => {
  const resolved = spawnSync('which', [tool], { encoding: 'utf8' }).stdout.trim()
  if (!resolved) {
    throw new Error(`missing command: ${tool}`)
  }
  return resolved
}

/** A frozen-runtime shim that answers exactly the calls the smoke harness makes. */
export function shim({
  nodeVersion = 'v24.21.0',
  cliVersion = `kk-studio-daemon ${DAEMON_VERSION}`,
  daemonVersion = DAEMON_VERSION,
  manifestVersion = DAEMON_VERSION,
  helpOk = true,
  validOk = true,
  invalidRejected = true,
  wrappedRejected = true,
  probeOut = 'koffi directory lock ok',
  probeExit = 0,
  nativeOk = true,
  nativeForeign = false,
  configMode = 'hold',
  pidFile = '',
} = {}) {
  const flag = (value) => (value ? '1' : '0')
  const values = {
    '@NODE_VERSION@': nodeVersion,
    '@CLI_VERSION@': cliVersion,
    '@DAEMON_VERSION@': daemonVersion,
    '@MANIFEST_VERSION@': manifestVersion,
    '@HELP_OK@': flag(helpOk),
    '@VALID_OK@': flag(validOk),
    '@INVALID_REJECTED@': flag(invalidRejected),
    '@WRAPPED_REJECTED@': flag(wrappedRejected),
    '@PROBE_OUT@': probeOut,
    '@PROBE_EXIT@': String(probeExit),
    '@NATIVE_OK@': flag(nativeOk),
    '@NATIVE_FOREIGN@': flag(nativeForeign),
    '@CONFIG_MODE@': configMode,
    '@PID_FILE@': pidFile,
    '@SLEEP@': absolute('sleep'),
    '@MKDIR@': absolute('mkdir'),
    '@TOUCH@': absolute('touch'),
    '@GREP@': absolute('grep'),
  }
  let template = readFileSync(SHIM_TEMPLATE, 'utf8')
  for (const [marker, value] of Object.entries(values)) {
    template = template.replaceAll(marker, value)
  }
  return template
}

/** Build a fake release archive at `file` carrying `node` as the frozen runtime. */
export function buildArchive(
  file,
  {
    node,
    includeRuntime = true,
    includeMain = true,
    includeKoffi = true,
    includeRg = true,
    includeIde = true,
    includeEntry = true,
    includeNative = true,
  },
) {
  const build = path.join(path.dirname(file), 'build')
  rmSync(build, { recursive: true, force: true })
  const host = `${hostOs()}-${hostArch()}`
  const koffiDir = path.join(build, 'node_modules/@koromix', KOFFI[host])
  mkdirSync(koffiDir, { recursive: true })
  writeFileSync(path.join(koffiDir, 'index.js'), 'module.exports = {}\n')
  const rgDir = path.join(build, 'node_modules/@vscode', RIPGREP[host], 'bin')
  mkdirSync(rgDir, { recursive: true })
  writeExecutable(path.join(rgDir, 'rg'), '#!/bin/sh\nprintf "ripgrep 15.0.0 (fixture)\\n"\n')
  if (includeIde) {
    mkdirSync(path.join(build, 'ide-extension'), { recursive: true })
    writeFileSync(path.join(build, 'ide-extension/extension.cjs'), 'module.exports = {}\n')
  }
  if (includeRuntime) {
    const runtime = path.join(build, 'runtime/code-server/lib/node')
    mkdirSync(path.dirname(runtime), { recursive: true })
    writeFileSync(runtime, node)
    chmodSync(runtime, 0o755)
    if (includeEntry) {
      const entry = path.join(build, 'runtime/code-server/out/node/entry.js')
      mkdirSync(path.dirname(entry), { recursive: true })
      writeFileSync(entry, '// frozen entry\n')
    }
    if (includeNative) {
      const native = path.join(build, 'runtime/code-server/lib/vscode/pty.node')
      mkdirSync(path.dirname(native), { recursive: true })
      writeFileSync(native, Buffer.from([0x7f, 0x45, 0x4c, 0x46]))
    }
  }
  if (includeMain) {
    writeFileSync(path.join(build, 'main.js'), 'export {}\n')
  }
  if (!includeKoffi) {
    rmSync(koffiDir, { recursive: true })
  }
  if (!includeRg) {
    rmSync(path.dirname(rgDir), { recursive: true })
  }
  writeFileSync(path.join(build, 'package.json'), `{"name":"@kk-studio/daemon","version":"${DAEMON_VERSION}","type":"module"}\n`)
  writeFileSync(path.join(build, 'runtime-manifest.json'), '{"schemaVersion":1}\n')
  writeFileSync(path.join(build, 'LICENSE'), 'Apache License 2.0\n')
  writeFileSync(path.join(build, 'THIRD_PARTY_NOTICES'), 'notices\n')

  const entries = treeEntries(build, '.')
    .slice(1)
    .map((entry) => ({ ...entry, name: entry.name.replace(/^\.\//, '') }))
  writeTarGz(file, entries)
  return file
}

/** Entries of `directory` whose name starts with `prefix`. */
export const entriesWithPrefix = (directory, prefix) => readdirSync(directory).filter((name) => name.startsWith(prefix))
