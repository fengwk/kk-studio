import koffi from 'koffi'
import type { SystemProxyResolver } from '../contracts.js'
import {
  SYSTEM_PROXY_CLOSED,
  SYSTEM_PROXY_CANCELLED,
  SYSTEM_PROXY_CANCEL_FAILED,
  SYSTEM_PROXY_HANDLE_FAILED,
  SYSTEM_PROXY_MALFORMED,
  SYSTEM_PROXY_UNAVAILABLE,
  SYSTEM_PROXY_UNSUPPORTED,
  systemProxyError,
  systemProxyErrorWithCode,
  SYSTEM_PROXY_LOOKUP_FAILED,
} from './system-proxy-errors.js'

/**
 * Linux 操作系统代理：真实 GLib/GIO `GProxyResolver`。
 *
 * <p>只支持**无认证 HTTP 代理**；`g_proxy_resolver_lookup` 返回的候选里，`direct://` 严格表示操作系统选择直连，
 * 非 http 协议（socks 等）或带认证的候选一律失败关闭，绝不悄悄降级。`g_io` 的默认 provider 在桌面后端缺失时可能是
 * 只返回 `direct://` 的 dummy，那本来就等价于「操作系统没有配置代理」；库本身缺失（`libgio-2.0.so.0` 载入失败）也
 * 只在这类**有证据的来源缺失**下才当作直连。查询错误则必须抛出，绝不隐性回退。
 *
 * <p>同步的 `g_proxy_resolver_lookup` 经 Koffi 派发到 worker 线程；`GCancellable` 支持跨线程取消。取消只是请求中断：
 * 仍然等待 lookup 结束，再统一释放 `gchar**`（`g_strfreev`）、`GError`（`g_error_free`）与 `GCancellable`
 * （`g_object_unref`）。默认解析器是借用单例，绝不 unref。
 */

/** 一次查询的归一化结果：要么拿到首个候选，要么拿到数值错误码。原生缓冲区已在端口内释放。 */
export type LinuxLookupOutcome = { kind: 'proxies'; first: string | null } | { kind: 'error'; code: number }

/** Linux 代理的真实端口：把 GLib/GIO 的细节与所有权都收敛在这一层。 */
export interface LinuxProxyBindings {
  /** 借用默认 `GProxyResolver` 单例；不可 unref，失败时返回 `null`。 */
  defaultResolver(): bigint | null
  /** 新建 `GCancellable`，由调用方负责 `unref`。 */
  createCancellable(): bigint | null
  /** 跨线程取消。 */
  cancelCancellable(cancellable: bigint): void
  /** 派发同步 `g_proxy_resolver_lookup`；端口负责解码并释放全部原生结果/错误后回调一次。 */
  startLookup(resolver: bigint, uri: string, cancellable: bigint, settle: (outcome: LinuxLookupOutcome) => void): void
  /** `g_object_unref`：void 调用，成败只能由调用本身是否抛错判断。 */
  unref(object: bigint): void
}

/** 进行中的查询：取消标志、原生取消令牌、「取消请求失败」记录与「已经释放完毕」的结算承诺。 */
interface ActiveLookup {
  readonly cancellable: bigint
  readonly finished: Promise<void>
  cancelled: boolean
  cancelFailed: boolean
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
 * Linux 操作系统代理解析器。
 *
 * <p>`null` 绑定表示**有证据的来源缺失**：桌面代理库不可用，操作系统没有可委托的代理来源，因此解析为直连；
 * 这之外的任何查询失败都会抛出。
 */
export class LinuxSystemProxyResolver implements SystemProxyResolver {
  private readonly bindings: LinuxProxyBindings | null
  private readonly active = new Set<ActiveLookup>()
  private closed = false
  private closing: Promise<void> | null = null

  constructor(bindings: LinuxProxyBindings | null) {
    this.bindings = bindings
  }

  async resolve(uri: string, signal: AbortSignal): Promise<string | null> {
    if (this.closed) {
      throw systemProxyError(SYSTEM_PROXY_CLOSED)
    }
    if (signal.aborted) {
      // 取消必须先于「来源缺失」判定：已取消的查询绝不因为缺少桌面代理库而被改写成直连。
      throw systemProxyError(SYSTEM_PROXY_CANCELLED)
    }
    const bindings = this.bindings
    if (bindings === null) {
      // 有证据的来源缺失（桌面代理库不存在）：操作系统没有代理来源可委托，等价于直连。
      return null
    }
    const resolver = bindings.defaultResolver()
    if (resolver === null) {
      throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
    }
    const cancellable = bindings.createCancellable()
    if (cancellable === null) {
      throw systemProxyError(SYSTEM_PROXY_UNAVAILABLE)
    }
    const finished = deferred()
    const active: ActiveLookup = { cancellable, finished: finished.promise, cancelled: false, cancelFailed: false }
    this.active.add(active)
    const onAbort = (): void => {
      active.cancelled = true
      try {
        bindings.cancelCancellable(active.cancellable)
      } catch {
        // 同步取消失败绝不能从 abort 派发中逃逸（那会变成 uncaught exception）；记在本次查询上，
        // 等 lookup 结束、原生资源释放后再以固定错误 reject 当前 resolve，绝不静默吞掉。
        active.cancelFailed = true
      }
    }
    signal.addEventListener('abort', onAbort, { once: true })
    try {
      const outcome = await new Promise<LinuxLookupOutcome>((settle) => {
        bindings.startLookup(resolver, uri, cancellable, settle)
      })
      if (active.cancelFailed) {
        throw systemProxyError(SYSTEM_PROXY_CANCEL_FAILED)
      }
      if (active.cancelled) {
        throw systemProxyError(SYSTEM_PROXY_CANCELLED)
      }
      if (outcome.kind === 'error') {
        throw systemProxyErrorWithCode(SYSTEM_PROXY_LOOKUP_FAILED, outcome.code)
      }
      return interpretLinuxProxy(outcome.first)
    } finally {
      signal.removeEventListener('abort', onAbort)
      // `g_object_unref` 是 void：从返回值无法判定成败，只有原生调用本身抛错才算「释放未被确认」。
      let releaseUnconfirmed = false
      try {
        bindings.unref(cancellable)
      } catch {
        releaseUnconfirmed = true
      }
      this.active.delete(active)
      finished.resolve()
      if (releaseUnconfirmed) {
        throw systemProxyError(SYSTEM_PROXY_HANDLE_FAILED)
      }
    }
  }

  close(): Promise<void> {
    if (this.closing === null) {
      this.closing = this.runClose()
    }
    return this.closing
  }

  private async runClose(): Promise<void> {
    this.closed = true
    const bindings = this.bindings
    const pending = [...this.active]
    let cancelFailed = false
    if (bindings !== null) {
      for (const lookup of pending) {
        lookup.cancelled = true
        try {
          bindings.cancelCancellable(lookup.cancellable)
        } catch {
          // 一个取消失败不能中断其余取消，也不能阻止等待所有查询真正结束；失败必须显式上报而不是吞掉。
          cancelFailed = true
        }
      }
    }
    // 每个进行中的查询仍会真正结束并释放原生缓冲区；调用方的错误已在各自 promise 上送达，这里只等待结算。
    await Promise.all(pending.map((lookup) => lookup.finished))
    if (cancelFailed) {
      throw systemProxyError(SYSTEM_PROXY_CANCEL_FAILED)
    }
  }
}

/**
 * 解释 `g_proxy_resolver_lookup` 的首个候选。
 *
 * <p>`direct://` 表示操作系统选择直连；只有 `http://` 能变成规范的 `http://host:port`。其它协议与带认证候选都失败关闭，
 * 解析不了同样抛出，绝不当作直连。
 */
export function interpretLinuxProxy(first: string | null): string | null {
  if (first === null) {
    return null
  }
  const boundary = first.indexOf('://')
  if (boundary <= 0) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const scheme = first.slice(0, boundary).toLowerCase()
  if (scheme === 'direct') {
    return null
  }
  if (scheme !== 'http') {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  let parsed: URL
  try {
    parsed = new URL(first)
  } catch {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  if (parsed.username !== '' || parsed.password !== '') {
    throw systemProxyError(SYSTEM_PROXY_UNSUPPORTED)
  }
  if (parsed.hostname === '') {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  const port = parsed.port === '' ? '80' : parsed.port
  return `http://${parsed.hostname}:${port}`
}

/**
 * 载入真实 GLib/GIO 绑定。
 *
 * <p>只在这三个可选系统桌面库都**存在**时返回端口。`null` 严格表示「共享库文件不存在」这一种可证据化的来源缺失；
 * 权限拒绝、无效 ELF/ABI、符号缺失等任何其它载入失败都向上抛出，绝不降级为直连。这里不修改任何操作系统配置。
 */
let cachedLinuxBindings: LinuxProxyBindings | null | undefined

/** 载入一个可选的系统共享库；只有「文件不存在」才返回 `null`，其余失败原样抛出。 */
function loadOptionalLibrary(name: string): ReturnType<typeof koffi.load> | null {
  try {
    return koffi.load(name)
  } catch (error) {
    if (isMissingSharedLibrary(error)) {
      return null
    }
    throw error
  }
}

/** Koffi 载入失败时唯一的「不存在」信号是 `cannot open shared object file: No such file or directory`。 */
function isMissingSharedLibrary(error: unknown): boolean {
  return error instanceof Error && error.message.includes('No such file or directory')
}

export function loadLinuxProxyBindings(): LinuxProxyBindings | null {
  if (cachedLinuxBindings !== undefined) {
    return cachedLinuxBindings
  }
  cachedLinuxBindings = loadLinuxProxyBindingsUncached()
  return cachedLinuxBindings
}

function loadLinuxProxyBindingsUncached(): LinuxProxyBindings | null {
  const gio = loadOptionalLibrary('libgio-2.0.so.0')
  const gobject = loadOptionalLibrary('libgobject-2.0.so.0')
  const glib = loadOptionalLibrary('libglib-2.0.so.0')
  if (gio === null || gobject === null || glib === null) {
    return null
  }
  const errorStruct = koffi.struct('GError', { domain: 'uint32_t', code: 'int32_t', message: 'void *' })
  const getDefault = gio.func('void *g_proxy_resolver_get_default()')
  const lookup = gio.func('void *g_proxy_resolver_lookup(void *resolver, const char *uri, void *cancellable, void *error)')
  const cancellableNew = gio.func('void *g_cancellable_new()')
  const cancellableCancel = gio.func('void g_cancellable_cancel(void *cancellable)')
  const objectUnref = gobject.func('void g_object_unref(void *object)')
  const errorFree = glib.func('void g_error_free(void *error)')
  const strfreev = glib.func('void g_strfreev(void *array)')
  return {
    defaultResolver: () => getDefault() as bigint | null,
    createCancellable: () => cancellableNew() as bigint | null,
    cancelCancellable: (cancellable) => {
      cancellableCancel(cancellable)
    },
    unref: (object) => {
      objectUnref(object)
    },
    startLookup: (resolver, uri, cancellable, settle) => {
      const errorSlot = koffi.alloc('void *', 1)
      koffi.encode(errorSlot, 'void *', null)
      lookup.async(resolver, uri, cancellable, errorSlot, (_failure: unknown, result: unknown) => {
        // 在回调内一次性解码并释放全部原生结果/错误：没有任何原生内存活过这次查询。
        const nativeError = koffi.decode(errorSlot, 'void *') as bigint | null
        let first: string | null = null
        let hasResult = false
        if (result !== null && result !== undefined) {
          try {
            first = koffi.decode(result, 'char *') as string | null
            hasResult = true
          } finally {
            strfreev(result as bigint)
          }
        }
        let code: number | null = null
        if (nativeError !== null) {
          try {
            const detail = koffi.decode(nativeError, errorStruct) as { code: number | bigint }
            code = Number(detail.code)
          } finally {
            errorFree(nativeError)
          }
        }
        settle(hasResult ? { kind: 'proxies', first } : { kind: 'error', code: code ?? -1 })
      })
    },
  }
}
