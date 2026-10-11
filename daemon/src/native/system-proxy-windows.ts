import koffi from 'koffi'
import type { SystemProxyResolver } from '../contracts.js'
import {
  SystemProxyError,
  SYSTEM_PROXY_CLOSED,
  SYSTEM_PROXY_CANCELLED,
  SYSTEM_PROXY_MALFORMED,
  SYSTEM_PROXY_RESOLVER_FAILED,
  SYSTEM_PROXY_RESULT_FAILED,
  SYSTEM_PROXY_SESSION_FAILED,
  SYSTEM_PROXY_CONFIG_FAILED,
  SYSTEM_PROXY_HANDLE_FAILED,
  SYSTEM_PROXY_TEARDOWN_FAILED,
  SYSTEM_PROXY_FATAL,
  SYSTEM_PROXY_UNSUPPORTED,
  SYSTEM_PROXY_UNAVAILABLE,
  SYSTEM_PROXY_LOOKUP_FAILED,
  systemProxyError,
  systemProxyErrorWithCode,
} from './system-proxy-errors.js'

/**
 * Windows 操作系统代理：当前用户的 IE/WinHTTP 代理配置 + WinHTTP 异步代理解析。
 *
 * <p>静态配置按 `WinHttpGetIEProxyConfigForCurrentUser` 的协议表解析：`http=`/`https=` 之外没有该协议的代理就是直连，
 * 真实 bypass（`<local>`、分号分隔、最左/最右通配符）按官方语义单独判定——这里不重搬 Core 的 `NO_PROXY` 解析。自动配置
 * （PAC URL 或 WPAD）走 `WinHttpCreateProxyResolver` + `WinHttpGetProxyForUrlEx` 的**异步**流程：端口在终态回调里先
 * 解码 `WINHTTP_PROXY_RESULT` 条目，再调用 `WinHttpFreeProxyResult` 释放原生内存，之后才把结果交给状态机。
 *
 * <p>只支持**无认证 HTTP 代理**：PAC/手动的非 http 协议、带认证候选一律失败关闭；PAC 脚本配置失败是错误而不是直连；只有
 * 「启用 WPAD 自动探测但网络里没有可发现的 PAC」这种官方 absence 语义才解析为直连（`AUTODETECTION_FAILED`）。
 *
 * <p>回调生命周期按 WinHTTP 的句柄协议：状态回调注册在 **session** 句柄上（非空 context + 句柄类通知），resolver 句柄
 * 继承同一回调；官方会给 resolver 句柄派发 `GETPROXYFORURL_COMPLETE`/`REQUEST_ERROR`，但**不会**为它派发
 * `HANDLE_CLOSING`，因此绝不假造一次；`session` 的 `HANDLE_CLOSING` 单独也不等于孩子回调消失。卸载只在能证明「每个查询
 * 都收到了它唯一的终态回调」且「session 句柄确实关闭并收到它最后一次回调」时发生。任一证据缺失（句柄关闭失败、终态超时、
 * closing 超时）都必须保留 registration、把解析器置为不可用终态（不再创建任何句柄），并让 `close` 显式失败——绝不提前
 * unregister 而留下踩到已释放蹦床的后台回调。
 */

/** `ERROR_FILE_NOT_FOUND`：当前用户没有任何代理配置，等价于直连。 */
const ERROR_FILE_NOT_FOUND = 2
/** `ERROR_IO_PENDING`：WinHttpGetProxyForUrlEx 异步启动成功的唯一返回值。 */
const ERROR_IO_PENDING = 997
/** `ERROR_WINHTTP_OPERATION_CANCELLED`：查询因句柄关闭被取消。 */
const ERROR_WINHTTP_OPERATION_CANCELLED = 12_017
/** `ERROR_WINHTTP_AUTODETECTION_FAILED`：WPAD 探测不到 PAC，是 absence 而不是错误。 */
const ERROR_WINHTTP_AUTODETECTION_FAILED = 12_180
const WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY = 4
const WINHTTP_FLAG_ASYNC = 0x1000_0000
const WINHTTP_OPTION_CONTEXT_VALUE = 45
const WINHTTP_AUTOPROXY_CONFIG_URL = 0x0000_0002
const WINHTTP_AUTOPROXY_AUTO_DETECT = 0x0000_0001
const WINHTTP_AUTO_DETECT_TYPE_DHCP = 0x0000_0001
const WINHTTP_AUTO_DETECT_TYPE_DNS_A = 0x0000_0002
const INTERNET_SCHEME_HTTP = 1
const WINHTTP_CALLBACK_STATUS_HANDLE_CREATED = 0x0000_0001
const WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING = 0x0000_0800
const WINHTTP_CALLBACK_STATUS_REQUEST_ERROR = 0x0020_0000
const WINHTTP_CALLBACK_STATUS_GETPROXYFORURL_COMPLETE = 0x0100_0000
/**
 * 终态回调必须订阅；`WINHTTP_CALLBACK_FLAG_HANDLES`（句柄创建 + 关闭）是收到 session `HANDLE_CLOSING` 的官方前提。
 */
const WINHTTP_CALLBACK_FLAG_HANDLES = WINHTTP_CALLBACK_STATUS_HANDLE_CREATED | WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING
const CALLBACK_FLAGS =
  WINHTTP_CALLBACK_STATUS_REQUEST_ERROR | WINHTTP_CALLBACK_STATUS_GETPROXYFORURL_COMPLETE | WINHTTP_CALLBACK_FLAG_HANDLES
/**
 * 非空 session context：`WinHttpCloseHandle` 只在「已注册状态回调 + 启用句柄类通知 + 句柄带非空 context」三者齐备时
 * 派发该句柄最后一次回调 `HANDLE_CLOSING`。句柄创建 API 本身没有 context 参数，只能经 `WINHTTP_OPTION_CONTEXT_VALUE` 设置。
 */
const SESSION_CONTEXT = 1n
/** `WINHTTP_INVALID_STATUS_CALLBACK` 是 `((WINHTTP_STATUS_CALLBACK)(-1L))`，64 位下即全 1 位模式。 */
const INVALID_STATUS_CALLBACK = -1n
const UNSIGNED_INVALID_STATUS_CALLBACK = 0xffff_ffff_ffff_ffffn
const USER_AGENT = 'kk-studio-daemon'
/** 等待「终态回调」与「session 最后回调」的预算；超时只上报，绝不提前 unregister。 */
const TEARDOWN_PROOF_BUDGET_MILLIS = 10_000

/** 当前用户的静态代理配置。 */
export interface WindowsProxyConfig {
  autoDetect: boolean
  autoConfigUrl: string | null
  proxy: string | null
  bypass: string | null
}

/** 自动代理解析选项（语义形式，原生结构体由端口负责拼装）。 */
export interface WindowsAutoProxyOptions {
  flags: number
  autoDetectFlags: number
  autoConfigUrl: string | null
}

/**
 * 单条 PAC/WPAD 结果条目（`WINHTTP_PROXY_RESULT_ENTRY`）。
 *
 * <p>`fProxy` 是「代理 vs 直连」的唯一判据；`fBypass` 官方只在 `fProxy == FALSE` 时生效，用来区分「内网绕过了已配置的
 * 代理」与「所有流量本来就直连」——两者都是直连，因此判定只看 `proxy`。
 */
export interface WindowsProxyEntry {
  /** `fProxy`：TRUE 表示该条目是代理候选，FALSE 表示该 URL 直连。 */
  proxy: boolean
  /** `fBypass`：仅在 `proxy === false` 时有意义，区分「绕过代理」与「全局直连」。 */
  bypass: boolean
  /** `INTERNET_SCHEME`：候选的代理协议。 */
  scheme: number
  host: string
  port: number
}

/** 状态回调归一化后的事件。 */
export type WindowsProxyStatusEvent = { kind: 'complete' } | { kind: 'error'; code: number } | { kind: 'closing' }

/** 状态回调签名（句柄与上下文已归一化为 BigInt）。 */
export type WindowsProxyStatusCallback = (handle: bigint, context: bigint, event: WindowsProxyStatusEvent) => void

/** Windows 代理的真实端口：把 WinHTTP/kernel32 的 ABI、结构与所有权都收敛在这一层。 */
export interface WindowsProxyBindings {
  /**
   * 读取当前用户 IE/WinHTTP 代理配置；返回的字符串由端口在内部 `GlobalFree`。
   * 任一返回字符串的释放未被确认（`GlobalFree` 返回原句柄或抛错）都会以固定文案失败，绝不伪称释放完成。
   */
  readCurrentUserProxyConfig(): WindowsProxyConfig
  /** 以 `WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY` + `WINHTTP_FLAG_ASYNC` 打开会话并设置非空 context。 */
  openAsyncSession(): bigint
  /** 在 session 上注册状态回调；返回的 token 用于之后 `unregisterCallback`。 */
  setStatusCallback(session: bigint, callback: WindowsProxyStatusCallback): unknown
  unregisterCallback(token: unknown): void
  createProxyResolver(session: bigint): bigint
  getProxyForUrlEx(resolver: bigint, url: string, options: WindowsAutoProxyOptions, context: bigint): number
  /** 读取一次查询的结果：先解码条目，再用 void 的 `WinHttpFreeProxyResult` 释放原生内存。 */
  readProxyResult(resolver: bigint): WindowsProxyEntry[]
  /** `WinHttpCloseHandle`；返回原始 BOOL，这是唯一可核验的释放结果。 */
  closeHandle(handle: bigint): boolean
  lastError(): number
}

/** 一次进行中的自动查询。 */
interface PendingLookup {
  readonly resolver: bigint
  readonly completion: Promise<string | null>
  /** 只有 WPAD 自动探测、没有固定 PAC URL 时，`AUTODETECTION_FAILED` 才按 absence 处理。 */
  readonly autoDetectionOnly: boolean
  handleClosed: boolean
  closeFailed: boolean
  /** 句柄释放失败时同线程读到的数值诊断码。 */
  releaseCode: number
  cancelled: boolean
  settled: boolean
  removeAbort: () => void
  resolve: (value: string | null) => void
  reject: (error: Error) => void
}

/** 一次性承诺。 */
function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/**
 * 异步 session 及其关闭承诺。
 *
 * <p>句柄与关闭承诺放在同一个对象里，保证它们的生命周期天然一致：session 一旦创建，关闭承诺就存在；
 * session 为 null 时绝不会残留半个状态。
 */
interface SessionState {
  readonly handle: bigint
  readonly closed: { promise: Promise<void>; resolve: () => void }
}

/** 保留本边界的安全诊断，未知 FFI 异常仅返回固定文案。 */
function asError(value: unknown): Error {
  return value instanceof SystemProxyError ? value : systemProxyError(SYSTEM_PROXY_SESSION_FAILED)
}

/** 展开嵌套的 AggregateError，保证一次上报里所有安全诊断都在同一层，绝不把主失败埋进嵌套。 */
function flattenFailures(failures: readonly unknown[]): Error[] {
  const flat: Error[] = []
  for (const failure of failures) {
    if (failure instanceof AggregateError) {
      flat.push(...flattenFailures(failure.errors))
    } else if (failure instanceof Error) {
      flat.push(failure)
    }
  }
  return flat
}

/** 多个安全诊断都要保留：单一失败直接抛出，多个用 AggregateError 承载（message 取主失败，不含任何原文）。 */
function throwFailures(failures: Error[]): never {
  const flat = flattenFailures(failures)
  const primary = flat[0]
  if (primary === undefined) {
    throw systemProxyError(SYSTEM_PROXY_SESSION_FAILED)
  }
  if (flat.length === 1) {
    throw primary
  }
  throw new AggregateError(flat, primary.message)
}

/**
 * 解释一次自动查询的首个结果条目。
 *
 * <p>条目数组按优先级排列，首个条目就是操作系统对该 URL 的首选决策：没有候选、或 `fProxy == FALSE`（无论 `fBypass`
 * 表示「内网绕过代理」还是「全局直连」）都表示直连。只接受无认证 HTTP 代理，其它协议或缺主机的候选失败关闭。
 */
export function interpretAutomatic(entries: WindowsProxyEntry[]): string | null {
  const entry = entries[0]
  if (entry === undefined || !entry.proxy) {
    // 没有候选，或 fProxy=FALSE 表示操作系统对该 URL 选择直连。
    return null
  }
  if (entry.scheme !== INTERNET_SCHEME_HTTP) {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  if (entry.host === '') {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  // `ProxyPort == 0` 表示操作系统没有给出显式端口，按 http 默认端口 80 规范化。
  const port = entry.port === 0 ? 80 : entry.port
  return `http://${formatProxyHost(entry.host)}:${port}`
}

/** IPv6 主机必须带方括号才是合法的 URI authority；WinHTTP 可能返回不带方括号的裸地址。 */
function formatProxyHost(host: string): string {
  if (host.includes(':') && !host.startsWith('[')) {
    return `[${host}]`
  }
  return host
}

/**
 * 手动静态代理表：`http=`/`https=` 协议项优先，其次才是裸 `host:port` 全局项；没有对应协议就是直连。
 */
export function selectManualProxy(proxyServer: string | null, bypass: string | null, uri: string): string | null {
  const target = parseTargetUri(uri)
  if (matchesBypass(bypass, target.host, target.scheme)) {
    return null
  }
  if (proxyServer === null || proxyServer.trim() === '') {
    return null
  }
  return findManualProxy(proxyServer, target.scheme)
}

/** 目标 URI 只能是等价 http/https URL；其它 scheme 视为解析失败。 */
function parseTargetUri(uri: string): { scheme: 'http' | 'https'; host: string } {
  let parsed: URL
  try {
    parsed = new URL(uri)
  } catch {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const scheme = parsed.protocol.replace(/:$/u, '')
  if (scheme !== 'http' && scheme !== 'https') {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  // http/https 是 special scheme：URL 解析器保证其主机名非空，否则直接解析失败。
  return { scheme, host: parsed.hostname }
}

/** 官方 bypass 语义：`<local>`、`*`、最左/最右通配符、可选 scheme 限定、精确主机。 */
export function matchesBypass(bypass: string | null, host: string, scheme: string): boolean {
  if (bypass === null) {
    return false
  }
  const target = host.toLowerCase()
  for (const raw of bypass.split(/[;\s]+/u)) {
    let token = raw.trim()
    if (token === '') {
      continue
    }
    const boundary = token.indexOf('://')
    if (boundary > 0) {
      if (token.slice(0, boundary).toLowerCase() !== scheme) {
        continue
      }
      token = token.slice(boundary + 3)
    }
    if (token === '') {
      continue
    }
    const pattern = token.toLowerCase()
    if (pattern === '<local>') {
      if (!target.includes('.')) {
        return true
      }
      continue
    }
    if (pattern === '*') {
      return true
    }
    if (pattern.startsWith('.')) {
      if (target.endsWith(pattern)) {
        return true
      }
      continue
    }
    const wildcard = pattern.indexOf('*')
    if (wildcard < 0) {
      if (target === pattern) {
        return true
      }
      continue
    }
    if (wildcard === 0) {
      if (target.endsWith(pattern.slice(1))) {
        return true
      }
      continue
    }
    if (wildcard === pattern.length - 1) {
      if (target.startsWith(pattern.slice(0, -1))) {
        return true
      }
    }
  }
  return false
}

function findManualProxy(proxyServer: string, targetScheme: 'http' | 'https'): string | null {
  let fallback: string | null = null
  for (const raw of proxyServer.split(/[;\s]+/u)) {
    const entry = raw.trim()
    if (entry === '') {
      continue
    }
    const separator = entry.indexOf('=')
    if (separator <= 0) {
      if (fallback === null) {
        fallback = parseManualProxyValue(entry, 80)
      }
      continue
    }
    const scheme = entry.slice(0, separator).toLowerCase()
    if (scheme !== 'http' && scheme !== 'https') {
      continue
    }
    const parsed = parseManualProxyValue(entry.slice(separator + 1), scheme === 'https' ? 443 : 80)
    if (scheme === targetScheme) {
      return parsed
    }
  }
  return fallback
}

/**
 * 解析手动代理项为规范 `http://host:port`；无法解释时失败关闭而不是当作直连。
 *
 * <p>`http=`/`https=` 是**目标协议**表键（值仍是同一个无认证 HTTP 代理），但值里字面的 `https://` 前缀指的是 HTTPS
 * 代理（TLS 到代理），本守护进程不支持，绝不降级成 `http://`。值只允许主机与端口（可带冗余的 `http://` 前缀与根路径
 * 尾斜杠）；额外的 path/query/fragment 或显式 0 端口都不能被悄悄丢弃。
 */
function parseManualProxyValue(value: string, defaultPort: number): string {
  let candidate = value.trim()
  const boundary = candidate.indexOf('://')
  if (boundary > 0) {
    if (candidate.slice(0, boundary).toLowerCase() !== 'http') {
      throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
    }
    candidate = candidate.slice(boundary + 3)
  }
  candidate = candidate.replace(/\/+$/u, '')
  if (candidate === '') {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  let parsed: URL
  try {
    parsed = new URL(`http://${candidate}`)
  } catch {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  if (parsed.username !== '' || parsed.password !== '') {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  if (
    (parsed.pathname !== '' && parsed.pathname !== '/') ||
    parsed.search !== '' ||
    parsed.hash !== '' ||
    parsed.port === '0'
  ) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return `http://${parsed.hostname}:${parsed.port === '' ? String(defaultPort) : parsed.port}`
}

/** 由静态配置推导自动代理解析选项；全程不启用自动登录，只接受无认证代理。 */
export function buildAutoProxyOptions(config: WindowsProxyConfig): WindowsAutoProxyOptions {
  let flags = 0
  if (config.autoConfigUrl !== null) {
    flags |= WINHTTP_AUTOPROXY_CONFIG_URL
  }
  let autoDetectFlags = 0
  if (config.autoDetect) {
    flags |= WINHTTP_AUTOPROXY_AUTO_DETECT
    autoDetectFlags = WINHTTP_AUTO_DETECT_TYPE_DHCP | WINHTTP_AUTO_DETECT_TYPE_DNS_A
  }
  return { flags, autoDetectFlags, autoConfigUrl: config.autoConfigUrl }
}

/** Windows 操作系统代理解析器状态机。 */
export class WindowsSystemProxyResolver implements SystemProxyResolver {
  private readonly loadBindings: () => WindowsProxyBindings
  private bindings: WindowsProxyBindings | null = null
  private readonly pending = new Map<bigint, PendingLookup>()
  private session: SessionState | null = null
  private callbackToken: unknown = null
  private nextContext = 1n
  private closed = false
  private fatal = false
  private closing: Promise<void> | null = null

  constructor(bindingsOrLoader: WindowsProxyBindings | (() => WindowsProxyBindings)) {
    this.loadBindings = typeof bindingsOrLoader === 'function' ? bindingsOrLoader : () => bindingsOrLoader
  }

  /** 第一次查询才加载 WinHTTP。close 在从未查询时不触碰原生库。 */
  private bindingsForResolve(): WindowsProxyBindings {
    if (this.bindings === null) {
      try {
        this.bindings = this.loadBindings()
      } catch (error) {
        throw asError(error)
      }
    }
    return this.bindings
  }

  /** 已由 resolve 加载的绑定。未查询时没有原生资源，调用方不得在这里触发加载。 */
  private loadedBindings(): WindowsProxyBindings {
    if (this.bindings === null) {
      throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
    }
    return this.bindings
  }

  async resolve(uri: string, signal: AbortSignal): Promise<string | null> {
    if (this.closed || this.fatal) {
      throw systemProxyError(this.fatal ? SYSTEM_PROXY_FATAL : SYSTEM_PROXY_CLOSED)
    }
    if (signal.aborted) {
      throw systemProxyError(SYSTEM_PROXY_CANCELLED)
    }
    const config = this.bindingsForResolve().readCurrentUserProxyConfig()
    if (config.autoConfigUrl !== null || config.autoDetect) {
      return this.resolveAutomatic(uri, config, signal)
    }
    return selectManualProxy(config.proxy, config.bypass, uri)
  }

  close(): Promise<void> {
    if (this.closing === null) {
      this.closing = this.runClose()
    }
    return this.closing
  }

  private resolveAutomatic(uri: string, config: WindowsProxyConfig, signal: AbortSignal): Promise<string | null> {
    const bindings = this.bindingsForResolve()
    const session = this.ensureSession()
    const resolver = bindings.createProxyResolver(session)
    const context = this.nextContext
    this.nextContext += 1n
    const options = buildAutoProxyOptions(config)
    const autoDetectionOnly = config.autoDetect && config.autoConfigUrl === null
    let resolvePromise!: (value: string | null) => void
    let rejectPromise!: (error: Error) => void
    const completion = new Promise<string | null>((resolve, reject) => {
      resolvePromise = resolve
      rejectPromise = reject
    })
    const op: PendingLookup = {
      resolver,
      completion,
      autoDetectionOnly,
      handleClosed: false,
      closeFailed: false,
      releaseCode: 0,
      cancelled: false,
      settled: false,
      removeAbort: () => undefined,
      resolve: resolvePromise,
      reject: rejectPromise,
    }
    const onAbort = (): void => this.cancelPending(op)
    op.removeAbort = () => signal.removeEventListener('abort', onAbort)
    this.pending.set(context, op)
    signal.addEventListener('abort', onAbort, { once: true })
    let status: number
    try {
      status = bindings.getProxyForUrlEx(resolver, uri, options, context)
    } catch {
      this.pending.delete(context)
      op.removeAbort()
      const closeFailed = this.releaseResolver(op)
      if (closeFailed) {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_HANDLE_FAILED, op.releaseCode)
      }
      throw systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
    }
    if (status !== ERROR_IO_PENDING) {
      this.pending.delete(context)
      op.removeAbort()
      const closeFailed = this.releaseResolver(op)
      if (closeFailed) {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_HANDLE_FAILED, op.releaseCode)
      }
      throw systemProxyErrorWithCode(SYSTEM_PROXY_LOOKUP_FAILED, status)
    }
    return completion
  }

  private ensureSession(): bigint {
    if (this.session !== null) {
      return this.session.handle
    }
    const handle = this.loadedBindings().openAsyncSession()
    const state: SessionState = { handle, closed: deferred() }
    try {
      this.callbackToken = this.loadedBindings().setStatusCallback(handle, (closedHandle, context, event) => {
        this.handleStatus(closedHandle, context, event)
      })
    } catch (registrationFailure) {
      // 回调注册失败：session 从未进入可用状态。关闭句柄；关闭未被确认时两个安全诊断都要保留。
      let released = false
      try {
        released = this.loadedBindings().closeHandle(handle)
      } catch {
        released = false
      }
      const primary = asError(registrationFailure)
      if (!released) {
        // 无法证明句柄已消失：解析器进入不可用终态，绝不再创建句柄。
        this.fatal = true
        throwFailures([primary, systemProxyError(SYSTEM_PROXY_TEARDOWN_FAILED)])
      }
      throw primary
    }
    this.session = state
    return handle
  }

  private cancelPending(op: PendingLookup): void {
    op.cancelled = true
    this.releaseResolver(op)
    // 终态回调（REQUEST_ERROR / OPERATION_CANCELLED）才是这次查询真正的结束。
  }

  /** 关闭 resolver 句柄并读回 BOOL；返回是否失败。 */
  private releaseResolver(op: PendingLookup): boolean {
    if (!op.handleClosed) {
      op.handleClosed = true
      try {
        op.closeFailed = !this.loadedBindings().closeHandle(op.resolver)
        // 与原生调用同线程立即取诊断码，避免之后被无关调用覆盖。
        op.releaseCode = op.closeFailed ? this.loadedBindings().lastError() : 0
      } catch {
        // 原生关闭本身抛错 = 无法证明句柄已释放：按失败处理，绝不当作成功。
        op.closeFailed = true
        op.releaseCode = this.loadedBindings().lastError()
      }
    }
    if (op.closeFailed) {
      // 句柄没能关闭：无法证明它不会再回调。保留 registration，并把解析器置为不可用。
      this.fatal = true
    }
    return op.closeFailed
  }

  private handleStatus(handle: bigint, context: bigint, event: WindowsProxyStatusEvent): void {
    if (event.kind === 'closing') {
      const state = this.session
      if (state !== null && handle === state.handle) {
        // session 的最后一次回调：证明它的句柄确实关闭、且此后再不会有该 session 的回调。
        state.closed.resolve()
      }
      return
    }
    const op = this.pending.get(context)
    if (op === undefined) {
      // 迟到的回调：该查询的句柄/上下文早已释放，直接忽略。
      return
    }
    this.pending.delete(context)
    op.settled = true
    op.removeAbort()
    let value: string | null = null
    let failure: Error | null = null
    if (event.kind === 'complete') {
      try {
        value = interpretAutomatic(this.loadedBindings().readProxyResult(op.resolver))
      } catch (error) {
        failure = asError(error)
      }
    } else if (op.cancelled || event.code === ERROR_WINHTTP_OPERATION_CANCELLED) {
      failure = systemProxyError(SYSTEM_PROXY_CANCELLED)
    } else if (op.autoDetectionOnly && event.code === ERROR_WINHTTP_AUTODETECTION_FAILED) {
      value = null
    } else {
      failure = systemProxyErrorWithCode(SYSTEM_PROXY_LOOKUP_FAILED, event.code)
    }
    const closeFailed = this.releaseResolver(op)
    if (closeFailed) {
      op.reject(systemProxyErrorWithCode(SYSTEM_PROXY_HANDLE_FAILED, op.releaseCode))
      return
    }
    if (failure !== null) {
      op.reject(failure)
      return
    }
    op.resolve(value)
  }

  private async runClose(): Promise<void> {
    this.closed = true
    const pending = [...this.pending.values()]
    for (const op of pending) {
      if (!op.settled) {
        op.cancelled = true
        this.releaseResolver(op)
      }
    }
    // 证据一：每个查询都必须收到它的**唯一终态**（COMPLETE 或 REQUEST_ERROR），否则无法证明它的回调不会再出现。
    const terminalsProven = await this.awaitTerminals(pending)
    const state = this.session
    if (state === null) {
      // 只有静态代理查询、从未建立异步 session：没有原生回调需要观察。
      return
    }
    // 证据二：session 句柄确实关闭，且收到它官方保证的最后一次回调（HANDLE_CLOSING）。
    // resolver 句柄本身不带 context、不会有 HANDLE_CLOSING，绝不假造一次；session closing 单独也不足以证明孩子回调消失。
    let handleClosed = false
    try {
      handleClosed = this.loadedBindings().closeHandle(state.handle)
    } catch {
      // 关闭抛错 = 无法确认句柄已释放：保留 registration，以固定文案失败，不回显原生异常原文。
      handleClosed = false
    }
    if (!handleClosed) {
      this.fatal = true
    }
    const closingProven = handleClosed && terminalsProven ? await this.awaitSessionClose(state) : false
    if (terminalsProven && closingProven && !this.fatal) {
      if (this.callbackToken !== null) {
        try {
          this.loadedBindings().unregisterCallback(this.callbackToken)
        } catch {
          // 卸载本身失败 = 无法证明蹦床已拆除：保留 registration 与 token，进入不可用终态并显式失败。
          this.fatal = true
          throw systemProxyError(SYSTEM_PROXY_TEARDOWN_FAILED)
        }
        this.callbackToken = null
      }
      return
    }
    // 任一证据缺失：保留 registration（避免后台线程回调踩到已释放的蹦床），解析器进入不可用终态，close 显式失败。
    this.fatal = true
    throw systemProxyError(SYSTEM_PROXY_TEARDOWN_FAILED)
  }

  /** 等待所有查询收到终态回调；超时即视为「无法证明不会再有回调」。 */
  private async awaitTerminals(pending: PendingLookup[]): Promise<boolean> {
    if (pending.length === 0) {
      return true
    }
    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), TEARDOWN_PROOF_BUDGET_MILLIS)
      timer.unref()
      void Promise.allSettled(pending.map((op) => op.completion)).then(() => {
        clearTimeout(timer)
        resolve(true)
      })
    })
  }

  /** 等待 session 的最后一次回调；超时即视为「无法证明不会再回调」。 */
  private async awaitSessionClose(state: SessionState): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), TEARDOWN_PROOF_BUDGET_MILLIS)
      timer.unref()
      void state.closed.promise.then(() => {
        clearTimeout(timer)
        resolve(true)
      })
    })
  }
}

/**
 * 载入真实 WinHTTP/kernel32 绑定。
 *
 * <p>只在 Windows 上被调用；所有库与结构体都是惰性载入的，非 Windows 平台引入本模块不会触碰 winhttp.dll。
 */
let cachedWindowsBindings: WindowsProxyBindings | null = null

export function loadWindowsProxyBindings(): WindowsProxyBindings {
  if (cachedWindowsBindings !== null) {
    return cachedWindowsBindings
  }
  const winhttp = koffi.load('winhttp.dll')
  const kernel32 = koffi.load('kernel32.dll')
  const ieConfigStruct = koffi.struct('WINHTTP_CURRENT_USER_IE_PROXY_CONFIG', {
    fAutoDetect: 'int32_t',
    lpszAutoConfigUrl: 'void *',
    lpszProxy: 'void *',
    lpszProxyBypass: 'void *',
  })
  const autoProxyOptionsStruct = koffi.struct('WINHTTP_AUTOPROXY_OPTIONS', {
    dwFlags: 'uint32_t',
    dwAutoDetectFlags: 'uint32_t',
    lpszAutoConfigUrl: 'char16_t *',
    lpvReserved: 'void *',
    dwReserved: 'uint32_t',
    fAutoLogonIfChallenged: 'int32_t',
  })
  const proxyResultStruct = koffi.struct('WINHTTP_PROXY_RESULT', {
    cEntries: 'uint32_t',
    pEntries: 'void *',
  })
  const proxyResultEntryStruct = koffi.struct('WINHTTP_PROXY_RESULT_ENTRY', {
    fProxy: 'int32_t',
    fBypass: 'int32_t',
    proxyScheme: 'int32_t',
    pwszProxy: 'void *',
    proxyPort: 'uint16_t',
  })
  const asyncResultStruct = koffi.struct('WINHTTP_ASYNC_RESULT', { dwResult: 'uintptr_t', dwError: 'uint32_t' })
  const statusCallbackProto = koffi.proto(
    'void __stdcall WinHttpStatusCallback(void *hInternet, uintptr_t dwContext, uint32_t dwInternetStatus, void *lpvStatusInformation, uint32_t dwStatusInformationLength)',
  )
  const WinHttpOpen = winhttp.func(
    'void *WinHttpOpen(const char16_t *agent, uint32_t accessType, const char16_t *proxy, const char16_t *bypass, uint32_t flags)',
  )
  const WinHttpSetOption = winhttp.func('int WinHttpSetOption(void *hInternet, uint32_t option, void *buffer, uint32_t length)')
  const WinHttpSetStatusCallback = winhttp.func(
    'void *WinHttpSetStatusCallback(void *hInternet, void *callback, uint32_t flags, uintptr_t reserved)',
  )
  const WinHttpCreateProxyResolver = winhttp.func('uint32_t WinHttpCreateProxyResolver(void *hSession, _Out_ void **phResolver)')
  const WinHttpGetProxyForUrlEx = winhttp.func(
    'uint32_t WinHttpGetProxyForUrlEx(void *hResolver, const char16_t *url, const WINHTTP_AUTOPROXY_OPTIONS *options, uintptr_t context)',
  )
  const WinHttpGetProxyResult = winhttp.func('uint32_t WinHttpGetProxyResult(void *hResolver, _Out_ WINHTTP_PROXY_RESULT *result)')
  const WinHttpFreeProxyResult = winhttp.func('void WinHttpFreeProxyResult(_Inout_ WINHTTP_PROXY_RESULT *result)')
  const WinHttpCloseHandle = winhttp.func('int WinHttpCloseHandle(void *handle)')
  const WinHttpGetIEProxyConfigForCurrentUser = winhttp.func(
    'int WinHttpGetIEProxyConfigForCurrentUser(_Out_ WINHTTP_CURRENT_USER_IE_PROXY_CONFIG *config)',
  )
  const GetLastError = kernel32.func('uint32_t GetLastError()')
  const GlobalFree = kernel32.func('void *GlobalFree(void *memory)')

  function pointerValue(value: unknown): bigint {
    if (typeof value === 'bigint') {
      return value
    }
    if (typeof value === 'number') {
      return BigInt(value)
    }
    return 0n
  }

  function decodeWideString(value: unknown): string | null {
    if (typeof value !== 'bigint') {
      return null
    }
    const text = koffi.decode.string16(value) as string
    return text === '' ? null : text
  }

  const bindings: WindowsProxyBindings = {
    readCurrentUserProxyConfig: () => {
      const config: Record<string, unknown> = {}
      if (Number(WinHttpGetIEProxyConfigForCurrentUser(config)) === 0) {
        const code = Number(GetLastError())
        if (code === ERROR_FILE_NOT_FOUND) {
          // 当前用户没有配置任何代理：这是配置缺失，等价于直连，而不是 API 失败。
          return { autoDetect: false, autoConfigUrl: null, proxy: null, bypass: null }
        }
        throw systemProxyErrorWithCode(SYSTEM_PROXY_CONFIG_FAILED, code)
      }
      let releaseFailed = false
      try {
        return {
          autoDetect: Number(config.fAutoDetect) !== 0,
          autoConfigUrl: decodeWideString(config.lpszAutoConfigUrl),
          proxy: decodeWideString(config.lpszProxy),
          bypass: decodeWideString(config.lpszProxyBypass),
        }
      } finally {
        // 每个非空字符串都必须尝试释放，绝不因为其中一个失败就跳过其余释放。
        // GlobalFree 成功返回 NULL、失败返回原句柄；返回非 NULL 或调用抛错都等于「释放未被确认」，
        // 必须以固定文案显式失败，而不是因为字符串已经解码完成就当作一切正常。
        for (const key of ['lpszAutoConfigUrl', 'lpszProxy', 'lpszProxyBypass']) {
          const pointer = config[key]
          if (typeof pointer !== 'bigint') {
            continue
          }
          try {
            // 逐个调用：任一释放失败也不能跳过其余字符串的释放。
            if (GlobalFree(pointer) !== null) {
              releaseFailed = true
            }
          } catch {
            releaseFailed = true
          }
        }
        if (releaseFailed) {
          throw systemProxyError(SYSTEM_PROXY_HANDLE_FAILED)
        }
      }
    },
    openAsyncSession: () => {
      const session = WinHttpOpen(USER_AGENT, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, null, null, WINHTTP_FLAG_ASYNC) as bigint | null
      if (session === null) {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_SESSION_FAILED, Number(GetLastError()))
      }
      // 会话已打开：从这里开始任何失败都必须先关闭会话，绝不让句柄泄漏。分配 slot 也必须在这个保护范围内。
      let contextSlot: bigint | null = null
      const failures: Error[] = []
      try {
        contextSlot = koffi.alloc('uintptr_t', 1)
        koffi.encode(contextSlot, 'uintptr_t', SESSION_CONTEXT)
        if (Number(WinHttpSetOption(session, WINHTTP_OPTION_CONTEXT_VALUE, contextSlot, koffi.sizeof('uintptr_t'))) === 0) {
          // 先捕获错误码：任何后续调用都会覆盖 GetLastError。
          failures.push(systemProxyErrorWithCode(SYSTEM_PROXY_SESSION_FAILED, Number(GetLastError())))
        }
      } catch (error) {
        failures.push(asError(error))
      } finally {
        if (contextSlot !== null) {
          try {
            koffi.free(contextSlot)
          } catch {
            // slot 释放未被确认：与主失败一起保留，绝不伪称已释放。
            failures.push(systemProxyError(SYSTEM_PROXY_HANDLE_FAILED))
          }
        }
      }
      if (failures.length > 0) {
        // 关闭这个不可用会话；关闭未被确认同样作为独立安全诊断保留，不覆盖主失败。
        try {
          if (!WinHttpCloseHandle(session)) {
            failures.push(systemProxyError(SYSTEM_PROXY_HANDLE_FAILED))
          }
        } catch {
          failures.push(systemProxyError(SYSTEM_PROXY_HANDLE_FAILED))
        }
        throwFailures(failures)
      }
      return session
    },
    setStatusCallback: (session, callback) => {
      const token = koffi.register((hInternet: unknown, dwContext: unknown, status: unknown, information: unknown) => {
        const code = Number(status)
        if (code === WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING) {
          callback(pointerValue(hInternet), 0n, { kind: 'closing' })
          return
        }
        if (code === WINHTTP_CALLBACK_STATUS_REQUEST_ERROR) {
          let errorCode = -1
          if (typeof information === 'bigint') {
            const detail = koffi.decode(information, asyncResultStruct) as { dwError: number | bigint }
            errorCode = Number(detail.dwError)
          }
          callback(pointerValue(hInternet), pointerValue(dwContext), { kind: 'error', code: errorCode })
          return
        }
        if (code === WINHTTP_CALLBACK_STATUS_GETPROXYFORURL_COMPLETE) {
          callback(pointerValue(hInternet), pointerValue(dwContext), { kind: 'complete' })
        }
      }, koffi.pointer(statusCallbackProto))
      const previous = WinHttpSetStatusCallback(session, token, CALLBACK_FLAGS, 0) as bigint | null
      if (previous === INVALID_STATUS_CALLBACK || previous === UNSIGNED_INVALID_STATUS_CALLBACK) {
        // 先捕获错误码，再 unregister：卸载调用会覆盖 GetLastError。
        const failures: Error[] = [systemProxyErrorWithCode(SYSTEM_PROXY_SESSION_FAILED, Number(GetLastError()))]
        try {
          koffi.unregister(token)
        } catch {
          // 注册本就无效，蹦床不会再被调用；卸载未被确认仍必须独立上报，绝不伪称已清理。
          failures.push(systemProxyError(SYSTEM_PROXY_HANDLE_FAILED))
        }
        throwFailures(failures)
      }
      return token
    },
    unregisterCallback: (token) => {
      koffi.unregister(token as bigint)
    },
    createProxyResolver: (session) => {
      const out: (bigint | null)[] = [null]
      const status = Number(WinHttpCreateProxyResolver(session, out))
      if (status !== 0) {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_RESOLVER_FAILED, status)
      }
      const handle = out[0]
      if (handle === null || handle === undefined) {
        throw systemProxyError(SYSTEM_PROXY_RESOLVER_FAILED)
      }
      return handle
    },
    getProxyForUrlEx: (resolver, url, options, context) => {
      const nativeOptions = {
        dwFlags: options.flags,
        dwAutoDetectFlags: options.autoDetectFlags,
        lpszAutoConfigUrl: options.autoConfigUrl,
        lpvReserved: null,
        dwReserved: 0,
        fAutoLogonIfChallenged: 0,
      }
      return Number(WinHttpGetProxyForUrlEx(resolver, url, nativeOptions, context))
    },
    readProxyResult: (resolver) => {
      const result: Record<string, unknown> = {}
      const status = Number(WinHttpGetProxyResult(resolver, result))
      if (status !== 0) {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_RESULT_FAILED, status)
      }
      try {
        const count = Number(result.cEntries)
        const entriesPointer = result.pEntries
        if (count <= 0 || typeof entriesPointer !== 'bigint') {
          return []
        }
        const decoded = koffi.decode(entriesPointer, proxyResultEntryStruct, count) as Array<Record<string, unknown>>
        return decoded.map((entry) => ({
          proxy: Number(entry.fProxy) !== 0,
          bypass: Number(entry.fBypass) !== 0,
          scheme: Number(entry.proxyScheme),
          host: typeof entry.pwszProxy === 'bigint' ? (koffi.decode.string16(entry.pwszProxy) as string) : '',
          port: Number(entry.proxyPort),
        }))
      } finally {
        WinHttpFreeProxyResult(result)
      }
    },
    closeHandle: (handle) => Number(WinHttpCloseHandle(handle)) !== 0,
    lastError: () => Number(GetLastError()),
  }
  cachedWindowsBindings = bindings
  return bindings
}
