/**
 * 进程级出站网络边界：平台代理策略与独占 HTTP 传输。
 *
 * <p>代理只来自平台设置，未配置时直连。仅接受无认证的 {@code http://host:port} 代理，HTTPS 由客户端使用
 * CONNECT。网段规则支持 IPv4 /0..32 与 IPv6 /0..128，只按目标 URL 中的数值 IP 逐位匹配，绝不解析域名。所有非法配置
 * 在构建期以固定、去敏的错误拒绝，不追加 DIRECT 兜底。
 *
 * <p>本模块是全进程 SDK initializer：{@link installOutboundNetwork} 只在有效运行命令启动时安装，必须早于任何网络客户端
 * 初始化；未安装时任何外网调用确定失败，绝不静默直连。{@link withLoopbackResponse} 是私有 code-server/IDE bridge 的
 * 独占直连通道，完全不读环境代理与全局 dispatcher。
 */

import net from 'node:net'
import { Readable } from 'node:stream'
import type { IncomingHttpHeaders } from 'node:http'
import { Agent, ProxyAgent, request, type Dispatcher } from 'undici'

/** 固定、去敏的代理配置错误；不携带任何配置值或原始解析异常。 */
export class ProxyConfigurationError extends Error {
  constructor() {
    super('Invalid HTTP proxy or bypass configuration')
    this.name = 'ProxyConfigurationError'
  }
}

/** 单次代理决策：直连或通过一个已选定的无认证 HTTP 代理。 */
export type ProxyChoice = { readonly kind: 'direct' } | { readonly kind: 'proxy'; readonly url: string }

/** 代理策略边界；{@code uri} 是完整目标 URL，ws/wss 分别映射到 http/https 代理。 */
export interface ProxyPolicy {
  select(uri: string, signal: AbortSignal): Promise<ProxyChoice>
}

interface Bypass {
  matches(host: string, literal: string, port: number): boolean
}

const DIRECT: ProxyChoice = { kind: 'direct' }
const DOMAIN_PATTERN = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$/
const NUMERIC_HOST_PATTERN = /^[0-9.]+$/
const IPV4_TEXT_PATTERN = /^[0-9]{1,3}$/
const IPV6_TEXT_PATTERN = /^[0-9a-fA-F:.]+$/
const PORT_PATTERN = /^[0-9]{1,5}$/
const PREFIX_PATTERN = /^[0-9]{1,3}$/

/** 主机、域名后缀与 127.* 规则；保留原有可选端口语义。 */
class HostBypass implements Bypass {
  constructor(
    private readonly host: string,
    private readonly port: number,
    private readonly prefix: boolean,
  ) {}

  matches(host: string, literal: string, port: number): boolean {
    void literal
    if (this.port !== -1 && this.port !== port) {
      return false
    }
    if (this.host === '*') {
      return true
    }
    if (this.host === host) {
      return true
    }
    if (this.prefix) {
      return /^127\.[0-9]+\.[0-9]+\.[0-9]+$/.test(host)
    }
    if (this.host.includes(':') || NUMERIC_HOST_PATTERN.test(this.host)) {
      return false
    }
    return host.endsWith(`.${this.host}`)
  }
}

/** IPv4/IPv6 CIDR 规则，只匹配目标 URL 中数值 IP 的相同地址族，且不受端口限制。 */
class CidrBypass implements Bypass {
  constructor(
    private readonly network: Uint8Array,
    private readonly prefix: number,
  ) {}

  matches(host: string, literal: string, port: number): boolean {
    void host
    void port
    const address = numericBytes(literal)
    if (address === null || address.length !== this.network.length) {
      return false
    }
    const fullBytes = Math.floor(this.prefix / 8)
    for (let index = 0; index < fullBytes; index++) {
      if (this.network[index] !== address[index]) {
        return false
      }
    }
    const remaining = this.prefix % 8
    if (remaining === 0) {
      return true
    }
    const mask = (0xff << (8 - remaining)) & 0xff
    return (this.network[fullBytes]! & mask) === (address[fullBytes]! & mask)
  }
}

class PlatformProxyPolicy implements ProxyPolicy {
  constructor(
    private readonly proxy: string | null,
    private readonly bypass: readonly Bypass[],
  ) {}

  async select(uri: string, signal: AbortSignal): Promise<ProxyChoice> {
    void signal
    if (typeof uri !== 'string' || uri.length === 0) {
      throw new Error('proxy target must be an absolute URL')
    }
    const schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(uri)
    const scheme = schemeMatch === null ? '' : schemeMatch[1]!.toLowerCase()
    const secure = scheme === 'https' || scheme === 'wss'
    const plain = scheme === 'http' || scheme === 'ws'
    if (!secure && !plain) {
      return DIRECT
    }
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(uri)) {
      throw new Error('proxy target must be an absolute URL')
    }
    let target: URL
    try {
      target = new URL(uri)
    } catch {
      throw new Error('proxy target must be an absolute URL')
    }
    const rawHost = target.hostname
    if (rawHost.length === 0) {
      throw new Error('proxy target host is required')
    }
    const host = normalizeHost(rawHost)
    const literal = stripBrackets(rawHost)
    const port = target.port === '' ? (secure ? 443 : 80) : Number.parseInt(target.port, 10)
    for (const rule of this.bypass) {
      if (rule.matches(host, literal, port)) {
        return DIRECT
      }
    }
    return this.proxy === null ? DIRECT : { kind: 'proxy', url: this.proxy }
  }
}

/** 平台指定的代理和绕过列表。 */
export const ProxyPolicy = {
  fixed(proxyUrl: string | null, noProxyHosts: string): ProxyPolicy {
    return new PlatformProxyPolicy(proxyUrl === null ? null : parseProxyUrl(proxyUrl), parseBypass(noProxyHosts))
  },
}

/** 严格解析无认证代理 URL：只接受 {@code http://host:explicit-port}，无 path/query/fragment/userinfo。 */
export function parseProxyUrl(value: string): string {
  const match = /^http:\/\/(\[[^\]]+\]|[^/?#@[\]:]+):([0-9]{1,5})$/i.exec(value)
  if (match === null) {
    throw new ProxyConfigurationError()
  }
  const rawHost = match[1]!
  const port = Number.parseInt(match[2]!, 10)
  if (port < 1 || port > 65535) {
    throw new ProxyConfigurationError()
  }
  if (normalizeHost(rawHost) === '*') {
    throw new ProxyConfigurationError()
  }
  const stripped = stripBrackets(rawHost)
  return `http://${stripped.includes(':') ? `[${stripped}]` : stripped}:${port}`
}

function parseBypass(value: string | null): Bypass[] {
  if (value === null) {
    throw new ProxyConfigurationError()
  }
  if (value.length === 0) {
    return []
  }
  const rules: Bypass[] = []
  for (const item of value.split(',')) {
    let rule = item.trim()
    if (rule.includes('/')) {
      rules.push(parseCidr(rule))
      continue
    }
    let port = -1
    if (rule.startsWith('[')) {
      const closing = rule.indexOf(']')
      if (closing < 0) {
        throw new ProxyConfigurationError()
      }
      const suffix = rule.slice(closing + 1)
      if (suffix.length > 0) {
        if (!suffix.startsWith(':')) {
          throw new ProxyConfigurationError()
        }
        port = parsePort(suffix.slice(1))
      }
      rule = rule.slice(1, closing)
      if (!rule.includes(':')) {
        throw new ProxyConfigurationError()
      }
    } else {
      const colon = rule.indexOf(':')
      if (colon >= 0 && colon === rule.lastIndexOf(':')) {
        port = parsePort(rule.slice(colon + 1))
        rule = rule.slice(0, colon)
      }
    }
    if (rule.startsWith('*.')) {
      rule = rule.slice(2)
      rejectNonDomainSuffix(rule)
    } else if (rule.startsWith('.')) {
      rule = rule.slice(1)
      rejectNonDomainSuffix(rule)
    }
    const prefix = rule === '127.*'
    rules.push(new HostBypass(prefix ? '127.' : normalizeHost(rule), port, prefix))
  }
  return rules
}

/** 严格解析 {@code address/prefix}：IPv4 /0..32、IPv6 /0..128，主机位可不为 0，不解析域名也不接受 zone ID。 */
function parseCidr(value: string): Bypass {
  const slash = value.indexOf('/')
  if (slash !== value.lastIndexOf('/')) {
    throw new ProxyConfigurationError()
  }
  const address = value.slice(0, slash)
  const prefixText = value.slice(slash + 1)
  if (address.length === 0 || !PREFIX_PATTERN.test(prefixText)) {
    throw new ProxyConfigurationError()
  }
  const prefix = Number.parseInt(prefixText, 10)
  if (address.includes(':')) {
    if (address.includes('[') || address.includes(']') || !IPV6_TEXT_PATTERN.test(address)) {
      throw new ProxyConfigurationError()
    }
    const network = ipv6Bytes(address)
    if (network === null || prefix > 128) {
      throw new ProxyConfigurationError()
    }
    return new CidrBypass(network, prefix)
  }
  const network = ipv4Bytes(address)
  if (network === null || prefix > 32) {
    throw new ProxyConfigurationError()
  }
  return new CidrBypass(network, prefix)
}

function parsePort(value: string): number {
  if (!PORT_PATTERN.test(value)) {
    throw new ProxyConfigurationError()
  }
  const port = Number.parseInt(value, 10)
  if (port < 1 || port > 65535) {
    throw new ProxyConfigurationError()
  }
  return port
}

function rejectNonDomainSuffix(host: string): void {
  if (host.includes('*') || host.includes(':') || NUMERIC_HOST_PATTERN.test(host)) {
    throw new ProxyConfigurationError()
  }
}

/** 严格十进制 IPv4：恰好 4 个 0..255 octet，不接受缩略或非数字形式。 */
function ipv4Bytes(value: string): Uint8Array | null {
  const octets = value.split('.')
  if (octets.length !== 4) {
    return null
  }
  const bytes = new Uint8Array(4)
  for (let index = 0; index < octets.length; index++) {
    const octet = octets[index]!
    if (!IPV4_TEXT_PATTERN.test(octet)) {
      return null
    }
    const number = Number.parseInt(octet, 10)
    if (number > 255) {
      return null
    }
    bytes[index] = number
  }
  return bytes
}

/** 解析纯数字 IPv6；IPv4-mapped 保持 16 字节地址族，不接受 zone ID。 */
function ipv6Bytes(value: string): Uint8Array | null {
  if (value.length === 0 || !IPV6_TEXT_PATTERN.test(value)) {
    return null
  }
  // 以标准数值 IPv6 校验结构（拒绝 `192.0.2.1::` 这类压缩前嵌入 IPv4 的形式）；zone 已被字符集拒绝。
  if (!net.isIPv6(value)) {
    return null
  }
  const halves = value.split('::')
  if (halves.length > 2) {
    return null
  }
  const head = parseIpv6Groups(halves[0]!)
  if (head === null) {
    return null
  }
  let groups: number[]
  if (halves.length === 1) {
    if (head.length !== 8) {
      return null
    }
    groups = head
  } else {
    const tail = parseIpv6Groups(halves[1]!)
    if (tail === null) {
      return null
    }
    const fill = 8 - head.length - tail.length
    if (fill < 1) {
      return null
    }
    groups = [...head, ...new Array<number>(fill).fill(0), ...tail]
  }
  const bytes = new Uint8Array(16)
  for (let index = 0; index < 8; index++) {
    const group = groups[index]!
    bytes[index * 2] = (group >> 8) & 0xff
    bytes[index * 2 + 1] = group & 0xff
  }
  return bytes
}

function parseIpv6Groups(segment: string): number[] | null {
  if (segment.length === 0) {
    return []
  }
  const tokens = segment.split(':')
  const groups: number[] = []
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]!
    if (token.length === 0) {
      return null
    }
    if (token.includes('.')) {
      if (index !== tokens.length - 1) {
        return null
      }
      const embedded = ipv4Bytes(token)
      if (embedded === null) {
        return null
      }
      groups.push((embedded[0]! << 8) | embedded[1]!, (embedded[2]! << 8) | embedded[3]!)
      continue
    }
    if (!/^[0-9a-fA-F]{1,4}$/.test(token)) {
      return null
    }
    groups.push(Number.parseInt(token, 16))
  }
  return groups
}

/** 仅对数值 IP 返回地址字节，域名或非数值 host 返回 null；不做 DNS 解析。 */
function numericBytes(host: string): Uint8Array | null {
  return host.includes(':') ? ipv6Bytes(host) : ipv4Bytes(host)
}

function stripBrackets(host: string): string {
  return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host
}

function normalizeHost(host: string): string {
  let value = stripBrackets(host).toLowerCase()
  if (value.includes(':')) {
    if (!IPV6_TEXT_PATTERN.test(value)) {
      throw new ProxyConfigurationError()
    }
    const bytes = ipv6Bytes(value)
    if (bytes === null) {
      throw new ProxyConfigurationError()
    }
    const groups: string[] = []
    for (let index = 0; index < 8; index++) {
      groups.push(((bytes[index * 2]! << 8) | bytes[index * 2 + 1]!).toString(16))
    }
    return groups.join(':')
  }
  if (value.endsWith('.')) {
    value = value.slice(0, -1)
  }
  if (value !== '*' && !DOMAIN_PATTERN.test(value)) {
    throw new ProxyConfigurationError()
  }
  return value
}

/**
 * 出站 HTTP 请求初始化；{@code redirect} 默认 fail-closed（拒绝意外重定向），需要时显式放行。
 */
export interface OutboundRequestInit {
  method?: string
  headers?: Record<string, string>
  body?: Dispatcher.DispatchOptions['body']
  redirect?: 'manual' | 'error'
  headersTimeout?: number
  bodyTimeout?: number
  connectTimeout?: number
}

export interface OutboundResponse {
  statusCode: number
  statusMessage: string
  headers: IncomingHttpHeaders
  body: Readable
  /** 释放底层 dispatcher；幂等，失败必须由调用方保留。 */
  dispose(): Promise<void>
}

/** 出站网络 SDK；所有外网调用都必须经它选择代理并独占 dispatcher。 */
export interface OutboundNetwork {
  selectProxy(uri: string, signal: AbortSignal): Promise<ProxyChoice>
  /** 打开一次响应；调用方负责消费/取消 body 后 dispose。 */
  openResponse(url: string, init: OutboundRequestInit, signal: AbortSignal): Promise<OutboundResponse>
  /** 显式 try/finally 的一次请求：use 返回后消费/取消 body 并 await 关闭 dispatcher。 */
  withResponse<T>(
    url: string,
    init: OutboundRequestInit,
    signal: AbortSignal,
    use: (response: OutboundResponse) => Promise<T>,
  ): Promise<T>
}

const REDIRECT_STATUS = new Set([301, 302, 303, 307, 308])

class DefaultOutboundNetwork implements OutboundNetwork {
  private readonly dispatchers = new Set<Dispatcher>()
  private readonly retires = new Map<Dispatcher, Promise<unknown>>()
  private readonly retired = new WeakSet<Dispatcher>()
  private readonly inflight = new Set<Promise<unknown>>()
  private readonly lifetime = new AbortController()
  private readonly idleWaiters: Array<() => void> = []
  private activeRequests = 0
  private closed = false
  private retiring = false
  private closePromise: Promise<void> | undefined

  constructor(private readonly policy: ProxyPolicy) {}

  selectProxy(uri: string, signal: AbortSignal): Promise<ProxyChoice> {
    return this.policy.select(uri, this.combine(signal))
  }

  async openResponse(url: string, init: OutboundRequestInit, signal: AbortSignal): Promise<OutboundResponse> {
    this.ensureOpen()
    this.activeRequests++
    try {
      const choice = await this.track(this.policy.select(url, this.combine(signal)))
      this.ensureOpen()
      const dispatcher = createDispatcher(choice, init.connectTimeout)
      this.dispatchers.add(dispatcher)
      // header 阶段也纳入 pending：关闭时必须等这次 dispatch 真正 settle。
      return await this.track(this.dispatch(dispatcher, url, init, this.combine(signal)))
    } catch (error) {
      this.releaseRequest()
      throw error
    }
  }

  async withResponse<T>(
    url: string,
    init: OutboundRequestInit,
    signal: AbortSignal,
    use: (response: OutboundResponse) => Promise<T>,
  ): Promise<T> {
    const response = await this.openResponse(url, init, signal)
    let result: T | undefined
    let primary: unknown
    let failed = false
    try {
      result = await use(response)
    } catch (error) {
      primary = error
      failed = true
    }
    let cleanup: unknown
    try {
      await response.dispose()
    } catch (error) {
      cleanup = error
    }
    if (failed) {
      // use 与 cleanup 同时失败时两者都要保留，不能相互覆盖。
      throw combineFailures(primary, cleanup)
    }
    if (cleanup !== undefined) {
      throw cleanup
    }
    return result as T
  }

  /** 关闭本次网络：中止生命周期并等待自身 pending 退场，再强制销毁独占资源（不无限等待未消费 body）。 */
  close(): Promise<void> {
    if (this.closePromise === undefined) {
      this.closePromise = this.dispose()
    }
    return this.closePromise
  }

  /** 独占 dispatcher 的一次交换：任何失败路径都释放，重定向按 redirect 选项裁决。 */
  private async dispatch(
    dispatcher: Dispatcher,
    url: string,
    init: OutboundRequestInit,
    signal: AbortSignal,
  ): Promise<OutboundResponse> {
    let response
    try {
      response = await request(url, {
        dispatcher,
        method: (init.method ?? 'GET') as Dispatcher.HttpMethod,
        headers: init.headers,
        body: init.body ?? null,
        signal,
        headersTimeout: init.headersTimeout,
        bodyTimeout: init.bodyTimeout,
      })
    } catch (error) {
      throw combineFailures(error, await this.retire(dispatcher, true))
    }
    // 未消费的 body 也不得让 abort/teardown 变成进程级 unhandled error；真实消费者仍能通过迭代收到错误。
    response.body.on('error', () => undefined)
    const redirect = init.redirect ?? 'error'
    if (redirect === 'error' && REDIRECT_STATUS.has(response.statusCode) && response.headers.location !== undefined) {
      destroyBody(response.body)
      throw combineFailures(
        new Error('outbound request refused an unexpected redirect'),
        await this.retire(dispatcher, false),
      )
    }
    let disposePromise: Promise<void> | undefined
    return {
      statusCode: response.statusCode,
      statusMessage: response.statusText,
      headers: response.headers,
      body: response.body,
      // 幂等且共享：并发/二次调用等待同一次关闭，不会在首次尚未结束时提前成功。
      dispose: () => {
        if (disposePromise === undefined) {
          disposePromise = (async () => {
            destroyBody(response.body)
            const cleanup = await this.retire(dispatcher, false)
            this.releaseRequest()
            if (cleanup !== undefined) {
              throw cleanup
            }
          })()
        }
        return disposePromise
      },
    }
  }

  private combine(signal: AbortSignal): AbortSignal {
    return AbortSignal.any([signal, this.lifetime.signal])
  }

  private ensureOpen(): void {
    if (this.closed) {
      throw new Error('outbound network is closed')
    }
    if (this.retiring) {
      throw new Error('outbound network is retired')
    }
  }

  /**
   * 进入退休：拒绝新请求，等在途请求释放后关闭。
   *
   * <p>这是配置热切换的边界：旧请求必须自然完成，不能因新配置直接取消。
   */
  retireWhenIdle(): Promise<void> {
    if (!this.retiring) {
      this.retiring = true
      if (this.activeRequests === 0) {
        for (const resolve of this.idleWaiters.splice(0)) {
          resolve()
        }
      }
    }
    return (async () => {
      if (this.activeRequests > 0) {
        await new Promise<void>((resolve) => this.idleWaiters.push(resolve))
      }
      await this.close()
    })()
  }

  private releaseRequest(): void {
    if (this.activeRequests > 0) {
      this.activeRequests--
    }
    if (this.retiring && this.activeRequests === 0) {
      for (const resolve of this.idleWaiters.splice(0)) {
        resolve()
      }
    }
  }

  private track<T>(promise: Promise<T>): Promise<T> {
    this.inflight.add(promise)
    const settle = (): void => {
      this.inflight.delete(promise)
    }
    promise.then(settle, settle)
    return promise
  }

  /**
   * 移出活跃集合后关闭/销毁；每个 dispatcher 只 retire 一次，并发调用共享同一 Promise。
   * 返回失败而非抛出；由 lifeAbort 触发的 expected request abort 不算 cleanup failure。
   */
  private retire(dispatcher: Dispatcher, force: boolean): Promise<unknown> {
    const existing = this.retires.get(dispatcher)
    if (existing !== undefined) {
      return existing
    }
    if (this.retired.has(dispatcher)) {
      return Promise.resolve(undefined)
    }
    this.retired.add(dispatcher)
    const promise = (async (): Promise<unknown> => {
      this.dispatchers.delete(dispatcher)
      try {
        if (force) {
          await dispatcher.destroy()
        } else {
          await dispatcher.close()
        }
        return undefined
      } catch (error) {
        return isExpectedAbort(error, this.lifetime.signal.aborted) ? undefined : error
      }
    })()
    this.retires.set(dispatcher, promise)
    const settle = (): void => {
      this.retires.delete(dispatcher)
    }
    promise.then(settle, settle)
    return promise
  }

  private async dispose(): Promise<void> {
    this.closed = true
    this.lifetime.abort()
    // 立即强制回收所有 owned dispatcher，取消仍处于 header/body 阶段的交换，避免等待死锁。
    for (const dispatcher of [...this.dispatchers]) {
      void this.retire(dispatcher, true)
    }
    // 等待所有 in-flight retire 真正 settle，再汇总其保留的 cleanup failure。
    const retireResults = await Promise.allSettled([...this.retires.values()])
    await Promise.allSettled([...this.inflight])
    const failures: unknown[] = []
    for (const result of retireResults) {
      if (result.status === 'rejected') {
        failures.push(result.reason)
      } else if (result.value !== undefined) {
        failures.push(result.value)
      }
    }
    throwFailures(failures)
  }
}

function createDispatcher(choice: ProxyChoice, connectTimeout: number | undefined): Dispatcher {
  const timeout = connectTimeout === undefined ? {} : { connectTimeout }
  return choice.kind === 'proxy'
    ? new ProxyAgent({ uri: choice.url, proxyTunnel: false, ...timeout })
    : new Agent(timeout)
}

/** 安全 teardown：已结束/已销毁的 body 不动；未消费的 body 直接销毁（创建期已吸收其 abort 错误）。 */
function destroyBody(body: Readable): void {
  if (body.destroyed || body.readableEnded) {
    return
  }
  body.destroy()
}

/** 保留主错误与清理错误：只有一个时直接返回，两者都有时用固定安全摘要聚合，避免覆盖。 */
function combineFailures(primary: unknown, cleanup: unknown): unknown {
  if (cleanup === undefined) {
    return primary
  }
  if (primary === undefined) {
    return cleanup
  }
  return new AggregateError([primary, cleanup], 'outbound request and cleanup both failed')
}

/** 由 lifeAbort 触发的取消属于预期请求中止，不是 cleanup failure；其它错误仍要保留。 */
function isExpectedAbort(error: unknown, lifetimeAborted: boolean): boolean {
  if (!lifetimeAborted || typeof error !== 'object' || error === null) {
    return false
  }
  const candidate = error as { name?: string; code?: string; message?: string }
  if (candidate.name === 'AbortError' || candidate.code === 'UND_ERR_ABORTED') {
    return true
  }
  return candidate.message === 'Operation cancelled' || candidate.message?.includes('aborted') === true
}

/** 关闭阶段的失败汇总：单个直接抛出，多个用固定安全摘要聚合。 */
function throwFailures(failures: readonly unknown[]): void {
  if (failures.length === 0) {
    return
  }
  if (failures.length === 1) {
    throw failures[0]
  }
  throw new AggregateError(failures, 'outbound network cleanup failed')
}

/** 已安装网络句柄；close 幂等并恢复先前网络。 */
export interface InstalledOutboundNetwork {
  readonly network: OutboundNetwork
  close(): Promise<void>
}

let currentNetwork: OutboundNetwork | undefined

export interface PreparedNetwork {
  /** 同步发布；返回旧句柄的异步收尾。 */
  publish(): Promise<void>
  /** 未发布候选的清理。 */
  retire(): Promise<void>
}

/**
 * 进程当前的卡片出站网络。
 *
 * <p>一次配置切换立即把新请求指向新句柄，旧句柄进入退休：不再接受新请求，等在途响应全部释放后关闭。这样
 * 旧 HTTP 请求按冻结代理完成，新请求使用新配置，不会因为热更新被硬取消。
 */
export class NetworkRuntime {
  private current: DefaultOutboundNetwork
  private closePromise: Promise<void> | undefined

  constructor(proxyUrl: string | null) {
    this.current = createDefaultNetwork(proxyUrl)
    currentNetwork = this.current
  }

  get network(): OutboundNetwork {
    return this.current
  }

  /** 准备一个尚未发布的代理句柄；发布前所有请求仍使用当前配置。 */
  prepare(proxyUrl: string | null): PreparedNetwork {
    const candidate = createDefaultNetwork(proxyUrl)
    return {
      publish: () => {
        const previous = this.current
        this.current = candidate
        currentNetwork = candidate
        return previous.retireWhenIdle()
      },
      retire: () => candidate.close(),
    }
  }

  /** 发布新代理策略；返回旧句柄的异步收尾，不阻塞当前请求。 */
  update(proxyUrl: string | null): Promise<void> {
    return this.prepare(proxyUrl).publish()
  }

  close(): Promise<void> {
    if (this.closePromise === undefined) {
      this.closePromise = (async () => {
        const closing = this.current
        if (currentNetwork === closing) {
          currentNetwork = undefined
        }
        await closing.close()
      })()
    }
    return this.closePromise
  }
}

function createDefaultNetwork(proxyUrl: string | null): DefaultOutboundNetwork {
  return new DefaultOutboundNetwork(ProxyPolicy.fixed(proxyUrl, ''))
}

/**
 * 当前进程已应用配置的出站网络；未配置时明确失败，绝不静默直连。
 */
export function getOutboundNetwork(): OutboundNetwork {
  if (currentNetwork === undefined) {
    throw new Error('outbound network is not initialized')
  }
  return currentNetwork
}

/** 测试与早期装配使用的直连安装入口；生产配置切换使用 {@link NetworkRuntime}。 */
export async function installOutboundNetwork(): Promise<InstalledOutboundNetwork> {
  const previous = currentNetwork
  const runtime = new NetworkRuntime(null)
  let closePromise: Promise<void> | undefined
  return {
    network: runtime.network,
    close(): Promise<void> {
      if (closePromise === undefined) {
        closePromise = (async () => {
          await runtime.close()
          if (currentNetwork === undefined) {
            currentNetwork = previous
          }
        })()
      }
      return closePromise
    },
  }
}

/** 私有 loopback 直连通道：只接受 127/::1，使用独占直连 Agent，绝不读环境代理或全局 dispatcher。 */
export async function withLoopbackResponse<T>(
  url: string,
  init: OutboundRequestInit,
  signal: AbortSignal,
  use: (response: OutboundResponse) => Promise<T>,
): Promise<T> {
  const target = new URL(url)
  if (!isLoopback(target.hostname)) {
    throw new Error('loopback transport requires a 127/::1 target')
  }
  const network = new DefaultOutboundNetwork(
    { select: () => Promise.resolve(DIRECT) },
  )
  return network.withResponse(url, init, signal, use)
}

function isLoopback(hostname: string): boolean {
  const host = stripBrackets(hostname).toLowerCase()
  if (host === '::1') {
    return true
  }
  return net.isIPv4(host) && host.split('.')[0] === '127'
}
