import koffi from 'koffi'
import { setImmediate } from 'node:timers/promises'
import type { SystemProxyResolver } from '../contracts.js'
import {
  SystemProxyError,
  SYSTEM_PROXY_CANCELLED,
  SYSTEM_PROXY_CANCEL_FAILED,
  SYSTEM_PROXY_CLOSED,
  SYSTEM_PROXY_HANDLE_FAILED,
  SYSTEM_PROXY_LOOKUP_FAILED,
  SYSTEM_PROXY_MALFORMED,
  SYSTEM_PROXY_RESULT_FAILED,
  SYSTEM_PROXY_UNAVAILABLE,
  SYSTEM_PROXY_UNSUPPORTED,
  systemProxyError,
  systemProxyErrorWithCode,
} from './system-proxy-errors.js'

/**
 * macOS 当前用户系统代理：CoreFoundation / CFNetwork。
 *
 * <p>`CFNetworkCopySystemProxySettings` 读取当前系统互联网代理设置，`CFNetworkCopyProxiesForURL` 按目标 URL 同步选出
 * 有序候选（含 bypass）。候选为 `kCFProxyTypeNone` 时才是操作系统明确直连。`kCFProxyTypeAutoConfigurationURL` 交给
 * `CFNetworkExecuteProxyAutoConfigurationURL`：CFNetwork 自己下载并执行 PAC，本进程不启动脚本解释器。官方取消方式是
 * 在完成前 `CFRunLoopSourceInvalidate`。
 *
 * <p>HTTP 与 HTTPS 目标的 HTTP 隧道代理均规范成 `http://host:port`；CF 的 HTTPS 类型表示隧道用途，不表示到代理的 TLS。
 * SOCKS、FTP 与带用户名或密码的候选失败关闭。
 * PAC 回调里的 `proxyList == NULL` 是错误，不是直连。查询、取消和 CF 对象释放失败都保留，绝不改写成直连。
 */

/** PAC 下载与执行的有界预算；超时后失效 source 并失败，不等待无界网络。 */
const PAC_BUDGET_MILLIS = 10_000

/** `kCFStringEncodingUTF8`。 */
const CF_STRING_ENCODING_UTF8 = 0x0800_0100

/** `kCFNumberSInt32Type`。 */
const CF_NUMBER_SINT32 = 3

/** 一次已复制的代理候选；CF 对象在复制时已经释放，这里只保留纯值。 */
export interface DarwinProxyCandidate {
  type: 'none' | 'http' | 'https' | 'socks' | 'ftp' | 'pac-url' | 'pac-script' | 'other'
  host: string | null
  port: number | null
  username: string | null
  password: string | null
  pacUrl: string | null
  pacScript: string | null
}

export type DarwinPacSource = { kind: 'url' | 'script'; value: string }

/** PAC 执行的归一化结果。`null` 列表是错误，不是直连。 */
export type DarwinPacOutcome =
  | { kind: 'proxies'; proxies: DarwinProxyCandidate[] }
  | { kind: 'error'; code: number }
  | { kind: 'cancelled' }
  | { kind: 'timeout' }

/** macOS 代理的真实端口：CF 所有权都在这一层释放。 */
export interface DarwinProxyBindings {
  /** 复制当前系统代理设置并按 URL 选出候选；返回前释放 settings、URL 与数组。 */
  copyProxiesForUrl(uri: string): DarwinProxyCandidate[]
  /**
   * 让 CFNetwork 下载并执行 PAC URL。
   *
   * <p>调用方通过 `cancelled` 请求中断。端口必须在回调返回前复制结果，并在结束前失效、移除并释放 run-loop source，
   * 再注销回调。
   */
  executePac(source: DarwinPacSource, targetUrl: string, cancelled: () => boolean): Promise<DarwinPacOutcome>
}

interface ActivePac {
  cancelled: boolean
  finished: Promise<void>
}

/** 保留本边界的安全诊断，未知 FFI 异常仅返回固定文案。 */
function asError(value: unknown): Error {
  return value instanceof SystemProxyError ? value : systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
}

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

function combineFailures(failures: Error[]): Error {
  const flat = flattenFailures(failures)
  const primary = flat[0]
  if (primary === undefined) {
    return systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
  }
  return flat.length === 1 ? primary : new AggregateError(flat, primary.message)
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/**
 * macOS 操作系统代理解析器。
 *
 * <p>构造不查询、不加载 CFNetwork。第一次 `resolve` 才取得绑定；加载失败是查询失败，不是直连。
 */
export class DarwinSystemProxyResolver implements SystemProxyResolver {
  private readonly loadBindings: () => DarwinProxyBindings
  private bindings: DarwinProxyBindings | null = null
  private readonly active = new Set<ActivePac>()
  private closed = false
  private closing: Promise<void> | null = null

  constructor(loadBindings: () => DarwinProxyBindings) {
    this.loadBindings = loadBindings
  }

  async resolve(uri: string, signal: AbortSignal): Promise<string | null> {
    if (this.closed) {
      throw systemProxyError(SYSTEM_PROXY_CLOSED)
    }
    if (signal.aborted) {
      throw systemProxyError(SYSTEM_PROXY_CANCELLED)
    }
    const bindings = this.bindingsForResolve()
    let candidates: DarwinProxyCandidate[]
    try {
      candidates = bindings.copyProxiesForUrl(uri)
    } catch (error) {
      throw asError(error)
    }
    return this.select(bindings, uri, candidates, signal, 0)
  }

  close(): Promise<void> {
    if (this.closing === null) {
      this.closing = this.runClose()
    }
    return this.closing
  }

  private bindingsForResolve(): DarwinProxyBindings {
    if (this.bindings === null) {
      try {
        this.bindings = this.loadBindings()
      } catch (error) {
        throw asError(error)
      }
    }
    return this.bindings
  }

  private async select(
    bindings: DarwinProxyBindings,
    uri: string,
    candidates: readonly DarwinProxyCandidate[],
    signal: AbortSignal,
    depth: number,
  ): Promise<string | null> {
    const first = candidates[0]
    if (first === undefined) {
      throw systemProxyError(SYSTEM_PROXY_RESULT_FAILED)
    }
    if (first.type === 'none') {
      return null
    }
    if (first.type === 'pac-url' || first.type === 'pac-script') {
      const value = first.type === 'pac-url' ? first.pacUrl : first.pacScript
      if (depth !== 0 || value === null) {
        // PAC 结果不能再次要求自动配置；缺少源内容也不能当成直连。
        throw systemProxyError(SYSTEM_PROXY_MALFORMED)
      }
      const source: DarwinPacSource = { kind: first.type === 'pac-url' ? 'url' : 'script', value }
      const resolved = await this.resolvePac(bindings, source, uri, signal)
      return this.select(bindings, uri, resolved, signal, depth + 1)
    }
    return interpretDarwinCandidate(first)
  }

  private async resolvePac(
    bindings: DarwinProxyBindings,
    source: DarwinPacSource,
    targetUrl: string,
    signal: AbortSignal,
  ): Promise<DarwinProxyCandidate[]> {
    const finished = deferred()
    const active: ActivePac = { cancelled: false, finished: finished.promise }
    this.active.add(active)
    const onAbort = (): void => {
      active.cancelled = true
    }
    signal.addEventListener('abort', onAbort, { once: true })
    let outcome: DarwinPacOutcome
    try {
      outcome = await bindings.executePac(source, targetUrl, () => active.cancelled || this.closed)
    } catch {
      outcome = { kind: 'error', code: -1 }
    } finally {
      signal.removeEventListener('abort', onAbort)
      this.active.delete(active)
      finished.resolve()
    }
    if (active.cancelled || outcome.kind === 'cancelled') {
      if (outcome.kind === 'timeout') {
        throw systemProxyError(SYSTEM_PROXY_CANCEL_FAILED)
      }
      throw systemProxyError(SYSTEM_PROXY_CANCELLED)
    }
    if (outcome.kind === 'timeout') {
      throw systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
    }
    if (outcome.kind === 'error') {
      throw systemProxyErrorWithCode(SYSTEM_PROXY_LOOKUP_FAILED, outcome.code)
    }
    if (outcome.proxies.length === 0) {
      throw systemProxyError(SYSTEM_PROXY_RESULT_FAILED)
    }
    return outcome.proxies
  }

  private async runClose(): Promise<void> {
    this.closed = true
    const pending = [...this.active]
    for (const lookup of pending) {
      lookup.cancelled = true
    }
    await Promise.all(pending.map((lookup) => lookup.finished))
  }
}

/**
 * 把一个已复制的候选解释成 daemon 接受的代理。
 *
 * <p>供测试直接覆盖解释规则。`null` 只来自 `none`。
 */
export function interpretDarwinCandidate(candidate: DarwinProxyCandidate): string | null {
  if (candidate.type === 'none') {
    return null
  }
  if (candidate.type !== 'http' && candidate.type !== 'https') {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  if (candidate.username !== null || candidate.password !== null) {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  if (candidate.host === null || candidate.host.length === 0 || candidate.port === null || !Number.isInteger(candidate.port) || candidate.port <= 0 || candidate.port > 65_535) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const host = candidate.host.includes(':') && !candidate.host.startsWith('[') ? `[${candidate.host}]` : candidate.host
  try {
    const proxy = new URL(`http://${host}:${candidate.port}`)
    if (proxy.username || proxy.password || proxy.pathname !== '/' || proxy.search || proxy.hash || !proxy.hostname) {
      throw systemProxyError(SYSTEM_PROXY_MALFORMED)
    }
    return `http://${proxy.hostname}:${candidate.port}`
  } catch {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
}

/** 真实 CFNetwork 绑定；只在第一次 resolve 时调用。 */
let cachedDarwinBindings: DarwinProxyBindings | undefined

export function loadDarwinProxyBindings(): DarwinProxyBindings {
  if (cachedDarwinBindings !== undefined) {
    return cachedDarwinBindings
  }
  cachedDarwinBindings = loadDarwinProxyBindingsUncached()
  return cachedDarwinBindings
}

/** 测试重置模块缓存之外的进程内缓存。 */
export function resetDarwinProxyBindingsForTests(): void {
  cachedDarwinBindings = undefined
}

interface DarwinNative {
  copySettings(): bigint | null
  copyProxies(url: bigint, settings: bigint): bigint | null
  executePac(source: bigint, targetUrl: bigint, callback: bigint, context: bigint, kind: DarwinPacSource['kind']): bigint | null
  runLoop(): bigint | null
  addSource(loop: bigint, source: bigint, mode: bigint): void
  removeSource(loop: bigint, source: bigint, mode: bigint): void
  invalidateSource(source: bigint): void
  runInMode(mode: bigint, seconds: number, returnAfterSourceHandled: boolean): number
  release(value: bigint): void
  arrayCount(array: bigint): number
  arrayAt(array: bigint, index: number): bigint | null
  dictionaryGet(dictionary: bigint, key: bigint): bigint | null
  typeId(value: bigint): bigint
  stringType(): bigint
  numberType(): bigint
  urlType(): bigint
  url(bytes: Buffer, length: number, encoding: number): bigint | null
  string(bytes: Buffer, length: number, encoding: number): bigint | null
  stringLength(value: bigint): number
  stringMaximumSize(length: number, encoding: number): number
  stringBytes(value: bigint, buffer: Buffer, size: number, encoding: number): boolean
  numberValue(value: bigint, type: number, out: Buffer): boolean
  errorCode(error: bigint): number
  stringEqual(left: bigint, right: bigint): boolean
  urlString(url: bigint): bigint | null
  defaultMode(): bigint
  key(name: string): bigint
  callback(handler: (client: bigint, proxies: bigint | null, error: bigint | null) => void): bigint
  unregister(callback: bigint): void
  allocContext(): bigint
  encodeContext(pointer: bigint, info: bigint | null): void
  free(pointer: bigint): void
}

function loadDarwinProxyBindingsUncached(): DarwinProxyBindings {
  const native = bindDarwinNative()
  return {
    copyProxiesForUrl: (uri) => copyProxies(native, uri),
    executePac: (source, targetUrl, cancelled) => executePac(native, source, targetUrl, cancelled),
  }
}

function bindDarwinNative(): DarwinNative {
  const core = koffi.load('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
  const network = koffi.load('/System/Library/Frameworks/CFNetwork.framework/CFNetwork')
  const copySettings = network.func('void *CFNetworkCopySystemProxySettings()')
  const copyProxies = network.func('void *CFNetworkCopyProxiesForURL(void *url, void *proxySettings)')
  const executePac = network.func(
    'void *CFNetworkExecuteProxyAutoConfigurationURL(void *proxyAutoConfigURL, void *targetURL, void *cb, void *clientContext)',
  )
  const executePacScript = network.func(
    'void *CFNetworkExecuteProxyAutoConfigurationScript(void *proxyAutoConfigScript, void *targetURL, void *cb, void *clientContext)',
  )
  const runLoop = core.func('void *CFRunLoopGetCurrent()')
  const addSource = core.func('void CFRunLoopAddSource(void *rl, void *source, void *mode)')
  const removeSource = core.func('void CFRunLoopRemoveSource(void *rl, void *source, void *mode)')
  const invalidateSource = core.func('void CFRunLoopSourceInvalidate(void *source)')
  const runInMode = core.func('int32_t CFRunLoopRunInMode(void *mode, double seconds, bool returnAfterSourceHandled)')
  const release = core.func('void CFRelease(void *cf)')
  const arrayCount = core.func('int64_t CFArrayGetCount(void *array)')
  const arrayAt = core.func('void *CFArrayGetValueAtIndex(void *array, int64_t idx)')
  const dictionaryGet = core.func('void *CFDictionaryGetValue(void *dictionary, void *key)')
  const typeId = core.func('uint64_t CFGetTypeID(void *cf)')
  const stringType = core.func('uint64_t CFStringGetTypeID()')
  const numberType = core.func('uint64_t CFNumberGetTypeID()')
  const urlType = core.func('uint64_t CFURLGetTypeID()')
  const url = core.func('void *CFURLCreateWithBytes(void *alloc, uint8_t *bytes, int64_t length, uint32_t encoding, void *base)')
  const string = core.func('void *CFStringCreateWithBytes(void *alloc, uint8_t *bytes, int64_t length, uint32_t encoding, bool isExternalRepresentation)')
  const stringLength = core.func('int64_t CFStringGetLength(void *string)')
  const stringMaximumSize = core.func('int64_t CFStringGetMaximumSizeForEncoding(int64_t length, uint32_t encoding)')
  const stringBytes = core.func('bool CFStringGetCString(void *string, void *buffer, int64_t size, uint32_t encoding)')
  const numberValue = core.func('bool CFNumberGetValue(void *number, int64_t type, void *value)')
  const errorCode = core.func('int64_t CFErrorGetCode(void *err)')
  const stringEqual = core.func('bool CFEqual(void *a, void *b)')
  const urlString = core.func('void *CFURLGetString(void *url)')
  const callbackType = koffi.proto('void PacCallback(void *client, void *proxyList, void *error)')
  const contextType = koffi.struct('CFStreamClientContext', {
    version: 'int64_t',
    info: 'void *',
    retain: 'void *',
    release: 'void *',
    copyDescription: 'void *',
  })
  const keys = new Map<string, bigint>()
  const key = (name: string): bigint => {
    const cached = keys.get(name)
    if (cached !== undefined) {
      return cached
    }
    const pointer = network.symbol(name) as bigint
    const value = koffi.decode(pointer, 'void *') as bigint | null
    if (value === null) {
      throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
    }
    keys.set(name, value)
    return value
  }
  return {
    copySettings: () => copySettings() as bigint | null,
    copyProxies: (target, settings) => copyProxies(target, settings) as bigint | null,
    executePac: (pac, target, callback, context, kind) => (kind === 'url' ? executePac : executePacScript)(pac, target, callback, context) as bigint | null,
    runLoop: () => runLoop() as bigint | null,
    addSource: (loop, source, mode) => {
      addSource(loop, source, mode)
    },
    removeSource: (loop, source, mode) => {
      removeSource(loop, source, mode)
    },
    invalidateSource: (source) => {
      invalidateSource(source)
    },
    runInMode: (mode, seconds, returnAfterSourceHandled) => Number(runInMode(mode, seconds, returnAfterSourceHandled)),
    release: (value) => {
      release(value)
    },
    arrayCount: (array) => Number(arrayCount(array)),
    arrayAt: (array, index) => arrayAt(array, index) as bigint | null,
    dictionaryGet: (dictionary, item) => dictionaryGet(dictionary, item) as bigint | null,
    typeId: (value) => BigInt(typeId(value) as bigint | number),
    stringType: () => BigInt(stringType() as bigint | number),
    numberType: () => BigInt(numberType() as bigint | number),
    urlType: () => BigInt(urlType() as bigint | number),
    url: (bytes, length, encoding) => url(null, bytes, length, encoding, null) as bigint | null,
    string: (bytes, length, encoding) => string(null, bytes, length, encoding, false) as bigint | null,
    stringLength: (value) => Number(stringLength(value)),
    stringMaximumSize: (length, encoding) => Number(stringMaximumSize(length, encoding)),
    stringBytes: (value, buffer, size, encoding) => Boolean(stringBytes(value, buffer, size, encoding)),
    numberValue: (value, type, out) => Boolean(numberValue(value, type, out)),
    errorCode: (error) => Number(errorCode(error)),
    stringEqual: (left, right) => Boolean(stringEqual(left, right)),
    urlString: (target) => urlString(target) as bigint | null,
    defaultMode: () => coreMode(core),
    key,
    callback: (handler) => koffi.register(handler, callbackType),
    unregister: (callback) => {
      koffi.unregister(callback)
    },
    allocContext: () => koffi.alloc(contextType, 1) as bigint,
    encodeContext: (pointer, info) => {
      koffi.encode(pointer, contextType, { version: 0, info, retain: null, release: null, copyDescription: null })
    },
    free: (pointer) => {
      koffi.free(pointer)
    },
  }
}

function coreMode(core: ReturnType<typeof koffi.load>): bigint {
  const pointer = core.symbol('kCFRunLoopDefaultMode') as bigint
  const value = koffi.decode(pointer, 'void *') as bigint | null
  if (value === null) {
    throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
  }
  return value
}

function copyProxies(native: DarwinNative, uri: string): DarwinProxyCandidate[] {
  const settings = native.copySettings()
  if (settings === null) {
    throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
  }
  let url: bigint | null = null
  let proxies: bigint | null = null
  let primary: Error | null = null
  let copied: DarwinProxyCandidate[] | null = null
  try {
    url = createUrl(native, uri)
    proxies = native.copyProxies(url, settings)
    if (proxies === null) {
      throw systemProxyError(SYSTEM_PROXY_RESULT_FAILED)
    }
    copied = readProxyArray(native, proxies)
  } catch (error) {
    primary = asError(error)
  }
  const releaseFailure = releaseAll(native, [proxies, url, settings])
  if (primary !== null) {
    throw releaseFailure === null ? primary : combineFailures([primary, releaseFailure])
  }
  if (releaseFailure !== null) {
    throw releaseFailure
  }
  return copied ?? []
}

function releaseAll(native: DarwinNative, values: Array<bigint | null>): Error | null {
  let failed = false
  for (const value of values) {
    if (value === null) {
      continue
    }
    try {
      native.release(value)
    } catch {
      failed = true
    }
  }
  return failed ? systemProxyError(SYSTEM_PROXY_HANDLE_FAILED) : null
}

async function executePac(
  native: DarwinNative,
  pacSource: DarwinPacSource,
  targetUrl: string,
  cancelled: () => boolean,
): Promise<DarwinPacOutcome> {
    let settled = false
    let outcome: DarwinPacOutcome = { kind: 'error', code: -1 }
    const finish = (next: DarwinPacOutcome): void => {
      if (!settled) {
        settled = true
        outcome = next
      }
    }
    let callback: bigint | null = null
    let context: bigint | null = null
    let pac: bigint | null = null
    let target: bigint | null = null
    let source: bigint | null = null
    let mode: bigint | null = null
    let loop: bigint | null = null
    let cleanupFailed = false
    const releaseAll = (): void => {
      if (source !== null && loop !== null && mode !== null) {
        try {
          native.invalidateSource(source)
        } catch {
          cleanupFailed = true
        }
        try {
          native.removeSource(loop, source, mode)
        } catch {
          cleanupFailed = true
        }
      }
      if (source !== null) {
        try {
          native.release(source)
        } catch {
          cleanupFailed = true
        }
      }
      for (const value of [pac, target]) {
        if (value !== null) {
          try {
            native.release(value)
          } catch {
            cleanupFailed = true
          }
        }
      }
      if (context !== null) {
        try {
          native.free(context)
        } catch {
          cleanupFailed = true
        }
      }
      if (callback !== null) {
        try {
          native.unregister(callback)
        } catch {
          cleanupFailed = true
        }
      }
    }
    try {
      callback = native.callback((_client, proxies, error) => {
        let outcome: DarwinPacOutcome
        try {
          if (error !== null) {
            outcome = { kind: 'error', code: native.errorCode(error) }
          } else if (proxies === null) {
            outcome = { kind: 'error', code: -1 }
          } else {
            outcome = { kind: 'proxies', proxies: readProxyArray(native, proxies) }
          }
        } catch {
          outcome = { kind: 'error', code: -1 }
        }
        finish(outcome)
      })
      context = native.allocContext()
      native.encodeContext(context, null)
      if (pacSource.kind === 'url') {
        pac = createUrl(native, pacSource.value)
      } else {
        const bytes = Buffer.from(pacSource.value, 'utf8')
        pac = native.string(bytes, bytes.length, CF_STRING_ENCODING_UTF8)
        if (pac === null) {
          throw systemProxyError(SYSTEM_PROXY_MALFORMED)
        }
      }
      target = createUrl(native, targetUrl)
      mode = native.defaultMode()
      loop = native.runLoop()
      if (loop === null) {
        throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
      }
      source = native.executePac(pac, target, callback, context, pacSource.kind)
      if (source === null) {
        throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
      }
      native.addSource(loop, source, mode)
      const deadline = Date.now() + PAC_BUDGET_MILLIS
      while (!settled && Date.now() < deadline) {
        if (cancelled()) {
          finish({ kind: 'cancelled' })
          break
        }
        native.runInMode(mode, 0.05, true)
        // CF 的轮询最多占用一个短时间片；让出 Node 事件循环以响应取消、关闭与并发请求。
        await setImmediate()
      }
      if (!settled) {
        finish(cancelled() ? { kind: 'cancelled' } : { kind: 'timeout' })
      }
    } catch {
      finish({ kind: 'error', code: -1 })
    } finally {
      releaseAll()
    }
    if (cleanupFailed) {
      return { kind: 'error', code: -1 }
    }
    return outcome
}

function createUrl(native: DarwinNative, value: string): bigint {
  const bytes = Buffer.from(value, 'utf8')
  const url = native.url(bytes, bytes.length, CF_STRING_ENCODING_UTF8)
  if (url === null) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return url
}

function readProxyArray(native: DarwinNative, array: bigint): DarwinProxyCandidate[] {
  const count = native.arrayCount(array)
  if (!Number.isSafeInteger(count) || count < 0) {
    throw systemProxyError(SYSTEM_PROXY_RESULT_FAILED)
  }
  const proxies: DarwinProxyCandidate[] = []
  for (let index = 0; index < count; index += 1) {
    const dictionary = native.arrayAt(array, index)
    if (dictionary === null) {
      throw systemProxyError(SYSTEM_PROXY_RESULT_FAILED)
    }
    proxies.push(readProxyDictionary(native, dictionary))
  }
  return proxies
}

function readProxyDictionary(native: DarwinNative, dictionary: bigint): DarwinProxyCandidate {
  const typeValue = native.dictionaryGet(dictionary, native.key('kCFProxyTypeKey'))
  if (typeValue === null) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const type = classifyType(native, typeValue)
  return {
    type,
    host: optionalString(native, dictionary, 'kCFProxyHostNameKey'),
    port: optionalPort(native, dictionary),
    username: optionalString(native, dictionary, 'kCFProxyUsernameKey'),
    password: optionalString(native, dictionary, 'kCFProxyPasswordKey'),
    pacUrl: optionalUrl(native, dictionary),
    pacScript: optionalString(native, dictionary, 'kCFProxyAutoConfigurationJavaScriptKey'),
  }
}

function classifyType(native: DarwinNative, value: bigint): DarwinProxyCandidate['type'] {
  const named: Array<[string, DarwinProxyCandidate['type']]> = [
    ['kCFProxyTypeNone', 'none'],
    ['kCFProxyTypeHTTP', 'http'],
    ['kCFProxyTypeHTTPS', 'https'],
    ['kCFProxyTypeSOCKS', 'socks'],
    ['kCFProxyTypeFTP', 'ftp'],
    ['kCFProxyTypeAutoConfigurationURL', 'pac-url'],
    ['kCFProxyTypeAutoConfigurationJavaScript', 'pac-script'],
  ]
  for (const [symbol, type] of named) {
    if (native.stringEqual(value, native.key(symbol))) {
      return type
    }
  }
  return 'other'
}

function optionalString(native: DarwinNative, dictionary: bigint, key: string): string | null {
  const value = native.dictionaryGet(dictionary, native.key(key))
  if (value === null) {
    return null
  }
  if (native.typeId(value) !== native.stringType()) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return readString(native, value)
}

function optionalPort(native: DarwinNative, dictionary: bigint): number | null {
  const value = native.dictionaryGet(dictionary, native.key('kCFProxyPortNumberKey'))
  if (value === null) {
    return null
  }
  if (native.typeId(value) !== native.numberType()) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const out = Buffer.alloc(4)
  if (!native.numberValue(value, CF_NUMBER_SINT32, out)) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return out.readInt32LE(0)
}

function optionalUrl(native: DarwinNative, dictionary: bigint): string | null {
  const value = native.dictionaryGet(dictionary, native.key('kCFProxyAutoConfigurationURLKey'))
  if (value === null) {
    return null
  }
  if (native.typeId(value) !== native.urlType()) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  // CFURLGetString 是 Get 规则：返回的 CFString 随 URL 存活，不能 CFRelease。
  const text = native.urlString(value)
  if (text === null) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return readString(native, text)
}

function readString(native: DarwinNative, value: bigint): string {
  const size = native.stringMaximumSize(native.stringLength(value), CF_STRING_ENCODING_UTF8)
  if (!Number.isSafeInteger(size) || size < 0) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const buffer = Buffer.alloc(size + 1)
  if (!native.stringBytes(value, buffer, buffer.length, CF_STRING_ENCODING_UTF8)) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const end = buffer.indexOf(0)
  return buffer.toString('utf8', 0, end < 0 ? buffer.length : end)
}
