import { constants as fsConstants, lstatSync, readFileSync, statSync, accessSync } from 'node:fs'
import { delimiter, dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import type { DaemonConfig, LspServerConfig } from './contracts.js'
import { ProtocolError, parseStrictJson } from './protocol.js'

/**
 * Daemon 连接与本地执行配置。
 *
 * <p>唯一外部配置来源是 {@code --config} 指定的 JSON 文件（{@code studioUrl}/{@code note}/{@code
 * bashExecutable}/{@code lsp}）；其父目录是运行数据目录，同目录的 {@code daemon.token} 是 owner-only 注册凭证
 * 文件。凭证文本只在按需读取时出现，绝不进入 {@link DaemonConfig} 或诊断输出。
 *
 * <p>配置错误只包含字段路径与规则，不含输入取值或解析器细节，供 {@code --check-config} 输出单行安全诊断。
 */

/** 统一项目版本；HELLO/READY 上报的 daemon 构建版本。 */
export const DAEMON_VERSION = '2.0.0'

/** 未显式配置时的 bash 可执行文件。 */
export const DEFAULT_BASH_EXECUTABLE = 'bash'

/** 配置校验失败的稳定前缀；installer 只解析该 marker 之后的安全诊断。 */
export const INVALID_CONFIGURATION_MARKER = 'Invalid daemon configuration'

const CONFIG_LIMITS = {
  maxBytes: 1024 * 1024,
  maxNestingDepth: 20,
  maxNumberLength: 64,
  maxStringBytes: 1024 * 1024,
}

const SERVER_ID_PATTERN = /^[A-Za-z0-9_.-]+$/
const NOTE_MAX_CHARS = 512

function invalid(path: string, rule: string): ProtocolError {
  return new ProtocolError(`${path}: ${rule}`)
}

/** 读取并完整校验配置：不创建目录、锁或进程。 */
export function loadDaemonConfig(configPath: string, distributionDir?: string): DaemonConfig {
  const file = requireAbsoluteConfigPath(configPath)
  const raw = readConfigFile(file)
  const parsed = parseConfigDocument(raw)
  const studioUrl = parseStudioOrigin(parsed.studioUrl)
  const note = parseNote(parsed.note)
  const bash = parseBashExecutable(parsed.bashExecutable)
  const lsp = parseLsp(parsed.lsp)
  const tokenPath = validateTokenFile(join(dirname(file), 'daemon.token'))
  const bashExecutable = resolveBash(bash)
  return {
    configPath: file,
    dataDir: dirname(file),
    tokenPath,
    studioUrl,
    gatewayUri: gatewayUri(studioUrl),
    bashExecutable,
    note,
    lsp,
    distributionDir: distributionDir ?? resolveDistributionDir(import.meta.url),
  }
}

/**
 * 纯校验：读取并校验配置、同目录凭证文件与解析后的 bash 可执行程序。
 *
 * <p>不创建目录、锁、连接，也不启动任何进程；失败抛出带有安全诊断的错误。
 */
export function checkConfig(configPath: string): void {
  const config = loadDaemonConfig(configPath)
  // 读取凭证内容以确认文件可读且非空；结果绝不输出。
  readRegistrationToken(config)
}

/** 按需读取注册凭证文本；不缓存到配置对象。 */
export function readRegistrationToken(config: Pick<DaemonConfig, 'tokenPath'>): string {
  let raw: string
  try {
    raw = readFileSync(config.tokenPath, 'utf8')
  } catch {
    throw new Error(`daemon.token: cannot read registration token file`)
  }
  const token = raw.trim()
  if (token === '') {
    throw new Error('daemon.token: registration token file must not be empty')
  }
  return token
}

/** 已编译程序的发行根：运行目录名为 {@code dist} 时取其父目录，否则取运行目录本身。 */
export function resolveDistributionDir(moduleUrl: string): string {
  const dir = dirname(fileURLToPath(moduleUrl))
  const base = dir.split(/[\\/]/).pop()
  return base === 'dist' ? dirname(dir) : dir
}

/** IDE 组件在发行根下的固定相对路径。 */
export const IDE_COMPONENT_RELATIVE_PATH = join('runtime', 'code-server')

function requireAbsoluteConfigPath(value: string): string {
  if (typeof value !== 'string' || value.trim() === '' || value.startsWith('--')) {
    throw invalid('daemon', 'configuration path must be absolute')
  }
  if (!isAbsolute(value)) {
    throw invalid('daemon', 'configuration path must be absolute')
  }
  return resolve(value)
}

function readConfigFile(file: string): string {
  try {
    const stats = statSync(file)
    if (!stats.isFile()) {
      throw new Error('not a file')
    }
    return readFileSync(file, 'utf8')
  } catch {
    throw invalid('daemon', 'cannot read valid JSON (duplicate keys are forbidden)')
  }
}

function parseConfigDocument(raw: string): Record<string, unknown> {
  let parsed: unknown
  try {
    parsed = parseStrictJson(raw, 'daemon', CONFIG_LIMITS)
  } catch {
    throw invalid('daemon', 'cannot read valid JSON (duplicate keys are forbidden)')
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw invalid('daemon', 'must be an object')
  }
  const root = parsed as Record<string, unknown>
  for (const field of Object.keys(root)) {
    if (field !== 'studioUrl' && field !== 'note' && field !== 'bashExecutable' && field !== 'lsp') {
      const suffix = SERVER_ID_PATTERN.test(field) ? `.${field}` : ''
      throw invalid(`daemon${suffix}`, 'unknown field')
    }
  }
  return root
}

function requireConfigText(value: unknown, path: string): string {
  if (value === undefined || value === null) {
    return ''
  }
  if (typeof value !== 'string') {
    throw invalid(path, 'must be a string')
  }
  return value
}

function parseStudioOrigin(value: unknown): string {
  const raw = requireConfigText(value, 'daemon.studioUrl')
  if (raw.trim() === '') {
    throw invalid('daemon.studioUrl', 'must be a nonblank string')
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw invalid('daemon.studioUrl', 'must be an HTTP(S) origin')
  }
  const scheme = url.protocol.slice(0, -1).toLowerCase()
  if (
    (scheme !== 'http' && scheme !== 'https') ||
    url.host === '' ||
    url.username !== '' ||
    url.password !== '' ||
    url.search !== '' ||
    url.hash !== '' ||
    (url.pathname !== '' && url.pathname !== '/')
  ) {
    throw invalid('daemon.studioUrl', 'must be an HTTP(S) origin without userinfo, path, query or fragment')
  }
  return `${scheme}://${url.host}`
}

function gatewayUri(studioOrigin: string): string {
  const scheme = studioOrigin.startsWith('https://') ? 'wss' : 'ws'
  const authority = studioOrigin.replace(/^https?:\/\//, '')
  return `${scheme}://${authority}/api/harness/environment-daemon/v1`
}

function parseNote(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalid('daemon.note', 'must be a string')
  }
  if (hasControls(value)) {
    throw invalid('daemon.note', 'must not contain control characters')
  }
  const note = value.trim()
  if (note === '' || note.length > NOTE_MAX_CHARS) {
    throw invalid('daemon.note', 'must be a nonblank single line of at most 512 characters')
  }
  return note
}

function parseBashExecutable(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalid('daemon.bashExecutable', 'must be a string')
  }
  if (value.trim() === '') {
    throw invalid('daemon.bashExecutable', 'must be a nonblank string')
  }
  if (hasControls(value)) {
    throw invalid('daemon.bashExecutable', 'must not contain control characters')
  }
  return value
}

function hasControls(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0
    if (code < 0x20 || code === 0x7f || code === 0x2028 || code === 0x2029) {
      return true
    }
  }
  return false
}

function parseLsp(value: unknown): Record<string, LspServerConfig> {
  if (value === undefined || value === null) {
    return {}
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw invalid('daemon.lsp', 'must be an object')
  }
  const lsp = value as Record<string, unknown>
  for (const field of Object.keys(lsp)) {
    if (field !== 'servers') {
      const suffix = SERVER_ID_PATTERN.test(field) ? `.${field}` : ''
      throw invalid(`daemon.lsp${suffix}`, 'unknown field')
    }
  }
  const servers = lsp.servers
  if (typeof servers !== 'object' || servers === null || Array.isArray(servers)) {
    throw invalid('daemon.lsp.servers', 'must be an object')
  }
  const serverMap = servers as Record<string, unknown>
  const ids = Object.keys(serverMap)
  if (ids.length === 0) {
    throw invalid('daemon.lsp.servers', 'must declare at least one server')
  }
  const result: Record<string, LspServerConfig> = {}
  for (const id of ids) {
    if (!SERVER_ID_PATTERN.test(id)) {
      throw invalid('daemon.lsp.servers', 'server IDs must match [A-Za-z0-9_.-]+')
    }
    const path = `daemon.lsp.servers.${id}`
    const server = serverMap[id]
    if (typeof server !== 'object' || server === null || Array.isArray(server)) {
      throw invalid(path, 'must be an object')
    }
    const serverObject = server as Record<string, unknown>
    for (const field of Object.keys(serverObject)) {
      if (
        field !== 'command' &&
        field !== 'extensions' &&
        field !== 'rootMarkers' &&
        field !== 'firstMatchMarkers'
      ) {
        throw invalid(path, 'unknown field')
      }
    }
    result[id] = {
      command: parseList(serverObject.command, `${path}.command`, true, false, false),
      extensions: parseList(serverObject.extensions, `${path}.extensions`, true, true, false),
      rootMarkers: parseList(serverObject.rootMarkers, `${path}.rootMarkers`, false, false, true),
      firstMatchMarkers: parseList(
        serverObject.firstMatchMarkers,
        `${path}.firstMatchMarkers`,
        false,
        false,
        true,
      ),
    }
  }
  return result
}

function parseList(
  value: unknown,
  path: string,
  required: boolean,
  extension: boolean,
  marker: boolean,
): string[] {
  if (value === undefined || value === null) {
    if (required) {
      throw invalid(path, 'must be a nonempty array of strings')
    }
    return []
  }
  if (!Array.isArray(value) || value.length === 0) {
    if (required) {
      throw invalid(path, 'must be a nonempty array of strings')
    }
    return []
  }
  const result: string[] = []
  for (let index = 0; index < value.length; index++) {
    const field = `${path}[${index}]`
    const entry = value[index]
    if (typeof entry !== 'string' || entry.trim() === '' || hasControls(entry)) {
      throw invalid(field, 'must be a nonblank string')
    }
    let normalized = entry
    if (extension) {
      normalized = normalized.trim().toLowerCase()
      if (normalized.length < 2 || !normalized.startsWith('.') || normalized.includes('/') || normalized.includes('\\')) {
        throw invalid(field, 'must be a file extension with a leading dot and no separators')
      }
    }
    if (marker) {
      normalized = normalized.trim()
      const parts = normalized.replace(/\\/g, '/').split('/')
      if (
        normalized.startsWith('/') ||
        normalized.startsWith('\\') ||
        normalized.includes(':') ||
        parts.some((part) => part === '..' || part === '.' || part === '')
      ) {
        throw invalid(field, 'must be project-relative without traversal')
      }
    }
    result.push(normalized)
  }
  return result
}

/** 校验凭证文件：绝对路径下的现存普通文件（不跟随符号链接），POSIX 上必须 owner-only。 */
function validateTokenFile(tokenPath: string): string {
  let stats
  try {
    stats = lstatSync(tokenPath)
  } catch {
    throw invalid('daemon.token', 'must be an existing regular file')
  }
  if (stats.isSymbolicLink() || !stats.isFile()) {
    throw invalid('daemon.token', 'must be a regular file, not a link or directory')
  }
  if (process.platform !== 'win32') {
    const mode = stats.mode & 0o777
    if ((mode & 0o077) !== 0) {
      throw invalid('daemon.token', 'must not grant group/other permissions')
    }
    if ((mode & 0o400) === 0) {
      throw invalid('daemon.token', 'must be readable by its owner')
    }
  }
  return tokenPath
}

/** 解析配置或默认 bash 可执行程序为宿主路径；无法解析时立即失败关闭。 */
function resolveBash(configured: string | null): string {
  const command = configured ?? DEFAULT_BASH_EXECUTABLE
  const resolved = resolveExecutable(command)
  if (resolved === null) {
    throw invalid(
      'daemon.bashExecutable',
      'must resolve to an executable on PATH or an absolute executable path',
    )
  }
  return resolved
}

/** 只做文件系统探测，不启动进程。 */
export function resolveExecutable(command: string): string | null {
  if (command.trim() === '') {
    return null
  }
  if (command.includes('/') || command.includes('\\') || isAbsolute(command)) {
    return isExecutableFile(command) ? resolve(command) : null
  }
  const pathValue = process.env.PATH ?? ''
  const extensions =
    process.platform === 'win32' ? (process.env.PATHEXT ?? '.EXE;.CMD;.BAT').split(';').filter((x) => x !== '') : ['']
  for (const directory of pathValue.split(delimiter)) {
    if (directory === '') {
      continue
    }
    for (const extension of extensions) {
      const candidate = join(directory, `${command}${extension}`)
      if (isExecutableFile(candidate)) {
        return resolve(candidate)
      }
    }
  }
  return null
}

function isExecutableFile(path: string): boolean {
  try {
    if (!statSync(path).isFile()) {
      return false
    }
    accessSync(path, fsConstants.X_OK)
    return true
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// CLI 参数与信息命令（纯逻辑，便于定向测试）
// ---------------------------------------------------------------------------

const INVALID_ARGUMENT_TRANSPORT = 'invalid daemon argument transport'

/**
 * 外部 argv 边界：Node 直接接收 Unicode 参数，不经过二次编码。
 *
 * <p>参数必须是字符串数组，且每个元素都是不含 NUL 的字符串。类型错误、非字符串与 NUL 一律拒绝，固定错误不回显原值。
 */
export function validateDirectArgs(args: string[]): string[] {
  if (!Array.isArray(args)) {
    throw new Error(INVALID_ARGUMENT_TRANSPORT)
  }
  for (const value of args) {
    if (typeof value !== 'string' || value.includes('\0')) {
      throw new Error(INVALID_ARGUMENT_TRANSPORT)
    }
  }
  return args
}

/** {@code --help} / {@code -h} 的完整用法文本。 */
export function usageText(): string {
  return `Usage: kk-studio-daemon --config <absolute-path>

Configuration:
  --config <absolute-path>         Load daemon JSON (studioUrl, note, bashExecutable, lsp).
                                   The sibling daemon.token holds the owner-only token;
                                   the configuration parent is the runtime data directory.
  --check-config <absolute-path>   Validate configuration, sibling token and resolved bash
                                   executable, then exit. No data creation, locking,
                                   connections, process spawning or LSP startup.
                                   Managed installation uses ~/.kk-studio/daemon.json.

Information:
  --help, -h                       Print this help and exit
  --version                        Print the daemon version and exit

Runtime heartbeat/reconnect intervals are fixed at PT15S/PT1S/PT30S.
The default bash executable is bash. Unknown or extra arguments fail closed.
`
}

/** 处理唯一的信息命令；{@code --help <其它参数>} 仍走配置解析并失败关闭。 */
export function printInfoCommand(args: string[]): { handled: boolean; output: string } {
  if (args.length !== 1) {
    return { handled: false, output: '' }
  }
  if (args[0] === '--help' || args[0] === '-h') {
    return { handled: true, output: usageText() }
  }
  if (args[0] === '--version') {
    return { handled: true, output: `kk-studio-daemon ${DAEMON_VERSION}\n` }
  }
  return { handled: false, output: '' }
}

const SAFE_CONFIGURATION_ERROR = /^daemon(?:\.[A-Za-z0-9_.\[\]-]+)?: [^\r\n]+$/

/** 把预检失败映射为单行安全诊断；不含堆栈、原始取值或换行。 */
export function invalidConfigurationLine(error: unknown): string {
  const message = error instanceof Error ? error.message : ''
  if (SAFE_CONFIGURATION_ERROR.test(message)) {
    return `${INVALID_CONFIGURATION_MARKER}: ${message}`
  }
  return `${INVALID_CONFIGURATION_MARKER}: configuration is invalid`
}
