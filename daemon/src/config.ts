import { constants as fsConstants, lstatSync, readFileSync, statSync, accessSync } from 'node:fs'
import { delimiter, dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import type { DaemonConfig, DaemonConfiguration, LspServerConfig } from './contracts.js'
import { ExecutableResolver } from './coding/lsp-discovery.js'
import { parseProxyUrl } from './network.js'
import { isCanonicalUuid } from './protocol/uuid.js'
import { isRealFile } from './native/fs-kind.js'
import { isWindows } from './native/host.js'
import { ProtocolError, parseStrictJson } from './protocol.js'
import { LONG_MAX } from './validation-constants.js'

/**
 * Daemon 连接引导与卡片配置。
 *
 * <p>唯一外部配置来源是 {@code --config} 指定的 JSON 文件，结构固定为
 * {@code {environmentId, version, daemonConfig:{studioUrl, bashExecutable, lsp, proxyUrl}}}；其父目录是运行数据目录，
 * 同目录的 {@code daemon.token} 是 owner-only 注册凭证文件。凭证文本只在按需读取时出现，绝不进入 {@link DaemonConfig}
 * 或诊断输出。
 *
 * <p>启动只校验控制连接所需的引导结构与凭证；bash/LSP 可执行文件的真实解析属于热应用候选校验，缓存中的程序暂时
 * 不存在也不能阻断 Daemon 建立控制通道接收修正。配置错误只包含字段路径与规则，不含输入取值或解析器细节。
 */

/** 统一项目版本；READY 上报的 daemon 构建版本。 */
export const DAEMON_VERSION = '2.0.1'

/** 未显式配置时的 bash 可执行文件。 */
export const DEFAULT_BASH_EXECUTABLE = 'bash'

/** 配置校验失败的稳定前缀；installer 只解析该 marker 之后的安全诊断。 */
export const INVALID_CONFIGURATION_MARKER = 'Invalid daemon configuration'

const CONFIG_LIMITS = {
  maxBytes: 1024 * 1024,
  maxNestingDepth: 20,
  maxNumberLength: 64,
}

const SERVER_ID_PATTERN = /^[A-Za-z0-9_.-]+$/
const VERSION_PATTERN = /^(0|[1-9][0-9]*)$/

/** 热应用候选校验失败；字段名与规则可安全上报，不含输入值。 */
export class EnvironmentConfigurationError extends Error {
  readonly code = 'VALIDATION_FAILED'

  constructor(
    readonly field: string | undefined,
    rule: string,
  ) {
    super(field === undefined ? rule : `${field}: ${rule}`)
    this.name = 'EnvironmentConfigurationError'
  }
}

function invalid(path: string, rule: string): ProtocolError {
  return new ProtocolError(`${path}: ${rule}`)
}

function candidateInvalid(field: string, rule: string): EnvironmentConfigurationError {
  return new EnvironmentConfigurationError(field, rule)
}

/** 安装目录、凭证与发行根：进程生命周期内不变。 */
export interface InstallationPaths {
  configPath: string
  dataDir: string
  tokenPath: string
  distributionDir: string
}

/** 读取并校验引导配置：不创建目录、锁、连接或进程。 */
export function loadDaemonConfig(configPath: string, distributionDir?: string): DaemonConfig {
  const file = requireAbsoluteConfigPath(configPath)
  const raw = readConfigFile(file)
  const parsed = parseConfigDocument(raw)
  const environmentId = requireCanonicalEnvironmentId(parsed.environmentId)
  const configVersion = parseConfigVersion(parsed.version)
  const configuration = parseDaemonConfiguration(parsed.daemonConfig)
  const paths = installationPaths(file, distributionDir)
  return bootstrapConfig(environmentId, configVersion, configuration, paths)
}

/**
 * 纯校验：读取并校验引导配置、同目录凭证文件与配置形状。
 *
 * <p>不创建目录、锁、连接，也不启动任何进程；bash/LSP 的真实解析留给热应用候选校验。
 */
export function checkConfig(configPath: string): void {
  const config = loadDaemonConfig(configPath)
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

function installationPaths(configPath: string, distributionDir?: string): InstallationPaths {
  const dataDir = dirname(configPath)
  return {
    configPath,
    dataDir,
    tokenPath: validateTokenFile(join(dataDir, 'daemon.token')),
    distributionDir: distributionDir ?? resolveDistributionDir(import.meta.url),
  }
}

function bootstrapConfig(
  environmentId: string,
  configVersion: bigint,
  configuration: DaemonConfiguration,
  paths: InstallationPaths,
): DaemonConfig {
  return {
    ...paths,
    environmentId,
    configVersion,
    studioUrl: configuration.studioUrl,
    gatewayUri: gatewayUri(configuration.studioUrl),
    // 启动不因缓存中的 bash 暂时不存在而失败；真实应用必须解析成功。
    bashExecutable: tryResolveBash(configuration.bashExecutable),
    bashExecutableSetting: configuration.bashExecutable,
    lsp: configuration.lsp,
    proxyUrl: configuration.proxyUrl,
  }
}

/** 把完整候选配置解析为执行上下文；任一声明/程序不可用即拒绝候选。 */
export function resolveDaemonConfiguration(
  paths: InstallationPaths,
  environmentId: string,
  configVersion: bigint,
  configuration: DaemonConfiguration,
): DaemonConfig {
  const studioUrl = parseStudioOrigin(configuration.studioUrl)
  const bashExecutable = resolveConfiguredBash(configuration.bashExecutable)
  validateLspExecutables(configuration.lsp)
  return {
    ...paths,
    environmentId,
    configVersion,
    studioUrl,
    gatewayUri: gatewayUri(studioUrl),
    bashExecutable,
    bashExecutableSetting: configuration.bashExecutable,
    lsp: configuration.lsp,
    proxyUrl: configuration.proxyUrl,
  }
}

/** 规范化本地缓存文本；固定字段顺序，供原子写入与内容比较。 */
export function serializeDaemonCache(config: DaemonConfig): string {
  const configuration: DaemonConfiguration = {
    studioUrl: config.studioUrl,
    bashExecutable: config.bashExecutableSetting,
    lsp: config.lsp,
    proxyUrl: config.proxyUrl,
  }
  return JSON.stringify({
    environmentId: config.environmentId,
    version: config.configVersion.toString(),
    daemonConfig: serializeConfiguration(configuration),
  })
}

/** 只序列化可编辑配置部分，字段顺序固定。 */
export function serializeConfiguration(configuration: DaemonConfiguration): Record<string, unknown> {
  const ids = Object.keys(configuration.lsp)
  return {
    studioUrl: configuration.studioUrl,
    bashExecutable: configuration.bashExecutable,
    lsp:
      ids.length === 0
        ? null
        : {
            servers: Object.fromEntries(
              ids.map((id) => {
                const server = configuration.lsp[id]!
                return [
                  id,
                  {
                    command: [...server.command],
                    ...(server.extensions.length === 0 ? {} : { extensions: [...server.extensions] }),
                    ...(server.rootMarkers.length === 0 ? {} : { rootMarkers: [...server.rootMarkers] }),
                    ...(server.firstMatchMarkers.length === 0
                      ? {}
                      : { firstMatchMarkers: [...server.firstMatchMarkers] }),
                  },
                ]
              }),
            ),
          },
    proxyUrl: configuration.proxyUrl,
  }
}

/** 控制 WS 地址：同一 Studio origin 的固定入口。 */
export function gatewayUri(studioOrigin: string): string {
  const scheme = studioOrigin.startsWith('https://') ? 'wss' : 'ws'
  const authority = studioOrigin.replace(/^https?:\/\//, '')
  return `${scheme}://${authority}/ws/daemon`
}

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
    if (!stats.isFile() || stats.size > CONFIG_LIMITS.maxBytes) {
      throw new Error('not a bounded regular file')
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
    if (field !== 'environmentId' && field !== 'version' && field !== 'daemonConfig') {
      throw invalid(`daemon.${field}`, 'unknown field')
    }
  }
  return root
}

function requireCanonicalEnvironmentId(value: unknown): string {
  if (!isCanonicalUuid(value)) {
    throw invalid('daemon.environmentId', 'must be a canonical lowercase UUID')
  }
  return value
}

function parseConfigVersion(value: unknown): bigint {
  if (typeof value !== 'string' || !VERSION_PATTERN.test(value)) {
    throw invalid('daemon.version', 'must be a canonical non-negative decimal string')
  }
  const version = BigInt(value)
  if (version > LONG_MAX) {
    throw invalid('daemon.version', 'must fit a signed 64-bit integer')
  }
  return version
}

/** 严格解析完整卡片配置；HTTP/本地/protobuf 三条入口共用这份结构校验。 */
export function parseDaemonConfiguration(
  value: unknown,
  path = 'daemon.daemonConfig',
): DaemonConfiguration {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalid(path, 'must be an object')
  }
  const root = value as Record<string, unknown>
  for (const field of Object.keys(root)) {
    if (field !== 'studioUrl' && field !== 'bashExecutable' && field !== 'lsp' && field !== 'proxyUrl') {
      throw invalid(`${path}.${field}`, 'unknown field')
    }
  }
  return {
    studioUrl: parseStudioOrigin(root.studioUrl, `${path}.studioUrl`),
    bashExecutable: parseBashExecutable(root.bashExecutable, `${path}.bashExecutable`),
    lsp: parseLsp(root.lsp, `${path}.lsp`),
    proxyUrl: parseProxy(root.proxyUrl, `${path}.proxyUrl`),
  }
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

function parseStudioOrigin(value: unknown, path = 'daemon.studioUrl'): string {
  const raw = requireConfigText(value, path)
  if (raw.trim() === '') {
    throw invalid(path, 'must be a nonblank string')
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    throw invalid(path, 'must be an HTTP(S) origin')
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
    throw invalid(path, 'must be an HTTP(S) origin without userinfo, path, query or fragment')
  }
  return `${scheme}://${url.host}`
}

function parseBashExecutable(value: unknown, path: string): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalid(path, 'must be a string')
  }
  if (value.trim() === '') {
    throw invalid(path, 'must be a nonblank string')
  }
  if (hasControls(value)) {
    throw invalid(path, 'must not contain control characters')
  }
  return value
}

function parseProxy(value: unknown, path: string): string | null {
  if (value === undefined || value === null) {
    return null
  }
  if (typeof value !== 'string' || value.trim() === '') {
    throw invalid(path, 'must be a nonblank string or null')
  }
  try {
    return parseProxyUrl(value)
  } catch {
    throw invalid(path, 'must be an unauthenticated http://host:port proxy without path, query or fragment')
  }
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

function parseLsp(value: unknown, path: string): Record<string, LspServerConfig> {
  if (value === undefined || value === null) {
    return {}
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw invalid(path, 'must be an object')
  }
  const lsp = value as Record<string, unknown>
  for (const field of Object.keys(lsp)) {
    if (field !== 'servers') {
      const suffix = SERVER_ID_PATTERN.test(field) ? `.${field}` : ''
      throw invalid(`${path}${suffix}`, 'unknown field')
    }
  }
  const servers = lsp.servers
  if (typeof servers !== 'object' || servers === null || Array.isArray(servers)) {
    throw invalid(`${path}.servers`, 'must be an object')
  }
  const serverMap = servers as Record<string, unknown>
  const ids = Object.keys(serverMap)
  if (ids.length === 0) {
    throw invalid(`${path}.servers`, 'must declare at least one server')
  }
  const result: Record<string, LspServerConfig> = {}
  for (const id of ids) {
    if (!SERVER_ID_PATTERN.test(id)) {
      throw invalid(`${path}.servers`, 'server IDs must match [A-Za-z0-9_.-]+')
    }
    const serverPath = `${path}.servers.${id}`
    const server = serverMap[id]
    if (typeof server !== 'object' || server === null || Array.isArray(server)) {
      throw invalid(serverPath, 'must be an object')
    }
    const serverObject = server as Record<string, unknown>
    for (const field of Object.keys(serverObject)) {
      if (
        field !== 'command' &&
        field !== 'extensions' &&
        field !== 'rootMarkers' &&
        field !== 'firstMatchMarkers'
      ) {
        throw invalid(serverPath, 'unknown field')
      }
    }
    result[id] = {
      command: parseList(serverObject.command, `${serverPath}.command`, true, false, false),
      extensions: parseList(serverObject.extensions, `${serverPath}.extensions`, true, true, false),
      rootMarkers: parseList(serverObject.rootMarkers, `${serverPath}.rootMarkers`, false, false, true),
      firstMatchMarkers: parseList(
        serverObject.firstMatchMarkers,
        `${serverPath}.firstMatchMarkers`,
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
  if (!isRealFile(stats)) {
    throw invalid('daemon.token', 'must be a regular file, not a link or directory')
  }
  if (!isWindows()) {
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

/** 解析配置或默认 bash 可执行程序；无法解析时拒绝候选。 */
function resolveConfiguredBash(configured: string | null): string {
  const command = configured ?? DEFAULT_BASH_EXECUTABLE
  const resolved = resolveExecutable(command)
  if (resolved === null) {
    throw candidateInvalid(
      'bashExecutable',
      'must resolve to an executable on PATH or an absolute executable path',
    )
  }
  return resolved
}

function tryResolveBash(configured: string | null): string | null {
  try {
    return resolveExecutable(configured ?? DEFAULT_BASH_EXECUTABLE)
  } catch {
    return null
  }
}

/** LSP 声明必须能在本机解析到命令；不做网络访问或进程启动。 */
function validateLspExecutables(lsp: Record<string, LspServerConfig>): void {
  for (const [id, server] of Object.entries(lsp)) {
    const command = server.command[0]
    if (command === undefined || ExecutableResolver.resolve(command) === undefined) {
      throw candidateInvalid(`lsp.servers.${id}.command`, 'must resolve to an executable on PATH or an absolute path')
    }
  }
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
  const extensions = executableExtensions()
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

/** Windows PATH 查找追加 PATHEXT；其它宿主只探测命令名本身。 */
function executableExtensions(): string[] {
  if (!isWindows()) {
    return ['']
  }
  return (process.env.PATHEXT ?? '.EXE;.CMD;.BAT').split(';').filter((extension) => extension !== '')
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
  --config <absolute-path>         Load daemon JSON (environmentId, version, daemonConfig).
                                   The sibling daemon.token holds the owner-only token;
                                   the configuration parent is the runtime data directory.
  --check-config <absolute-path>   Validate connection bootstrap JSON and sibling token,
                                   then exit. No data creation, locking, connections,
                                   process spawning or LSP startup.
                                   Managed installation uses ~/.kk-studio/environments/<uuid>/daemon.json.

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
