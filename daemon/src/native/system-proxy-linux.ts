import koffi from 'koffi'
import type { SystemProxyResolver } from '../contracts.js'
import {
  SystemProxyError,
  SYSTEM_PROXY_CLOSED,
  SYSTEM_PROXY_CANCELLED,
  SYSTEM_PROXY_CANCEL_FAILED,
  SYSTEM_PROXY_FATAL,
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

/** 一次查询的归一化结果：候选或数值错误码，外加「原生资源释放未被确认」。原生缓冲区已在端口内释放。 */
export type LinuxLookupOutcome =
  | { kind: 'proxies'; first: string | null; cleanupFailed: boolean }
  | { kind: 'error'; code: number; cleanupFailed: boolean }

/** Linux 代理的真实端口：把 GLib/GIO 的细节与所有权都收敛在这一层。 */
export interface LinuxProxyBindings {
  /** 借用默认 `GProxyResolver` 单例；不可 unref，失败时返回 `null`。 */
  defaultResolver(): bigint | null
  /** 新建 `GCancellable`，由调用方负责 `unref`。 */
  createCancellable(): bigint | null
  /** 跨线程取消。 */
  cancelCancellable(cancellable: bigint): void
  /** 派发同步 `g_proxy_resolver_lookup`；端口负责解码、释放全部原生结果/错误/slot 后**恰好结算一次**。 */
  startLookup(resolver: bigint, uri: string, cancellable: bigint, settle: (outcome: LinuxLookupOutcome) => void): void
  /** `g_object_unref`：void 调用，成败只能由调用本身是否抛错判断。 */
  unref(object: bigint): void
}

/** 进行中的查询：取消标志、原生取消令牌、「取消请求失败」记录与「已经结束」的结算承诺。 */
interface ActiveLookup {
  readonly cancellable: bigint
  readonly finished: Promise<void>
  cancelled: boolean
  cancelFailed: boolean
}

/** 保留本边界的安全诊断，未知 FFI 异常仅返回固定文案。 */
function asError(value: unknown): Error {
  return value instanceof SystemProxyError ? value : systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
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

/** 全部安全诊断都必须保留：单一失败原样返回，多个用 AggregateError 承载（message 取首个，即主失败）。 */
function combineFailures(failures: Error[]): Error {
  const flat = flattenFailures(failures)
  const primary = flat[0]
  if (primary === undefined) {
    return systemProxyError(SYSTEM_PROXY_LOOKUP_FAILED)
  }
  return flat.length === 1 ? primary : new AggregateError(flat, primary.message)
}

/** 主失败与释放失败都必须保留。 */
function aggregateFailure(primary: Error, secondary: Error | null): Error {
  return combineFailures(secondary === null ? [primary] : [primary, secondary])
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
  private readonly loadBindings: () => LinuxProxyBindings | null
  private bindings: LinuxProxyBindings | null = null
  private bindingsLoaded = false
  private readonly active = new Set<ActiveLookup>()
  private closed = false
  private closing: Promise<void> | null = null
  /** 一次未被确认的原生释放：解析器进入不可用终态，绝不再分配新句柄，且 `close` 必须显式失败。 */
  private cleanupFailure: Error | null = null

  constructor(bindingsOrLoader: LinuxProxyBindings | null | (() => LinuxProxyBindings | null)) {
    this.loadBindings = typeof bindingsOrLoader === 'function' ? bindingsOrLoader : () => bindingsOrLoader
  }

  /** 第一次查询才加载绑定。`null` 仍是有证据的桌面库缺失。加载期的其它失败必须抛出，不能改写成直连。 */
  private bindingsForResolve(): LinuxProxyBindings | null {
    if (!this.bindingsLoaded) {
      try {
        this.bindings = this.loadBindings()
      } catch (error) {
        throw asError(error)
      }
      this.bindingsLoaded = true
    }
    return this.bindings
  }

  async resolve(uri: string, signal: AbortSignal): Promise<string | null> {
    if (this.closed) {
      throw systemProxyError(SYSTEM_PROXY_CLOSED)
    }
    if (this.cleanupFailure !== null) {
      throw systemProxyError(SYSTEM_PROXY_FATAL)
    }
    if (signal.aborted) {
      // 取消必须先于「来源缺失」判定：已取消的查询绝不因为缺少桌面代理库而被改写成直连。
      throw systemProxyError(SYSTEM_PROXY_CANCELLED)
    }
    const bindings = this.bindingsForResolve()
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
    let settled: { value: string | null } | { failure: Error }
    let releaseFailure: Error | null = null
    try {
      const outcome = await new Promise<LinuxLookupOutcome>((settle) => {
        try {
          bindings.startLookup(resolver, uri, cancellable, settle)
        } catch (error) {
          // 端口违反「恰好结算一次」时，原生异常也不能以未固定文案逃逸或让 resolve 永久挂起。
          settle({ kind: 'error', code: -1, cleanupFailed: true })
          void error
        }
      })
      settled = this.interpretOutcome(outcome, active)
    } catch (error) {
      settled = { failure: asError(error) }
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
        // 不重复释放（UAF），但绝不声称「已释放」：记住失败、停止再分配句柄，并让 close 显式失败。
        releaseFailure = systemProxyError(SYSTEM_PROXY_HANDLE_FAILED)
        this.cleanupFailure ??= releaseFailure
      }
    }
    if ('value' in settled) {
      if (releaseFailure !== null) {
        throw releaseFailure
      }
      return settled.value
    }
    throw aggregateFailure(settled.failure, releaseFailure)
  }

  private interpretOutcome(outcome: LinuxLookupOutcome, active: ActiveLookup): { value: string | null } | { failure: Error } {
    // 取消、查询结果与原生清理失败各自独立：全部收集，绝不因为一个失败而丢弃另一个。
    const failures: Error[] = []
    if (active.cancelFailed) {
      failures.push(systemProxyError(SYSTEM_PROXY_CANCEL_FAILED))
    } else if (active.cancelled) {
      // 取消已成功请求：lookup 就是被取消的，这是调用方视角的主因。
      failures.push(systemProxyError(SYSTEM_PROXY_CANCELLED))
    }
    if (outcome.kind === 'error') {
      failures.push(systemProxyErrorWithCode(SYSTEM_PROXY_LOOKUP_FAILED, outcome.code))
    }
    if (outcome.cleanupFailed) {
      const failure = systemProxyError(SYSTEM_PROXY_HANDLE_FAILED)
      failures.push(failure)
      this.cleanupFailure ??= failure
    }
    if (failures.length === 0 && outcome.kind === 'proxies') {
      try {
        return { value: interpretLinuxProxy(outcome.first) }
      } catch (error) {
        failures.push(asError(error))
      }
    }
    if (failures.length === 0) {
      return { value: null }
    }
    return { failure: combineFailures(failures) }
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
    // 每个进行中的查询仍会真正结束并释放原生缓冲区；调用方的错误已在各自 promise 上送达，这里只等待结束。
    await Promise.all(pending.map((lookup) => lookup.finished))
    // 释放未被确认优先于取消失败：它意味着解析器已不可用，绝不能在这里伪称清理完成。
    if (this.cleanupFailure !== null) {
      throw this.cleanupFailure
    }
    if (cancelFailed) {
      throw systemProxyError(SYSTEM_PROXY_CANCEL_FAILED)
    }
  }
}

/**
 * 解释 `g_proxy_resolver_lookup` 的首个候选。
 *
 * <p>直连必须是 GIO 规范的 `direct://` token（不许 `direct://host` 这种凭空直连）；只有无认证的 `http://` 代理能变成规范的
 * `http://host:port`。其它协议与带认证候选都失败关闭，解析不了同样抛出，绝不当作直连。
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
    // 只有 `direct://` 本身是直连 token；`direct://host` 之类的取值语义不明，失败关闭。
    if (first.slice(boundary + 3) !== '') {
      throw systemProxyError(SYSTEM_PROXY_MALFORMED)
    }
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
  // OS provider 常见形态（无显式端口 -> 补 80、根路径尾斜杠）可接受；额外的 path/query/fragment 或 0 端口都不能
  // 被悄悄丢弃，一律失败关闭。
  if (
    (parsed.pathname !== '' && parsed.pathname !== '/') ||
    parsed.search !== '' ||
    parsed.hash !== '' ||
    parsed.port === '0'
  ) {
    throw systemProxyError(SYSTEM_PROXY_MALFORMED)
  }
  return `http://${parsed.hostname}:${parsed.port === '' ? '80' : parsed.port}`
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
      // 端口契约要求恰好结算一次；回调内任何原生异常都不允许逃逸（Koffi 回调里的未捕获异常不会结算，
      // resolve/close 会永久挂起）。
      let settled = false
      const settleOnce = (outcome: LinuxLookupOutcome): void => {
        if (!settled) {
          settled = true
          settle(outcome)
        }
      }
      let errorSlot: bigint
      try {
        // 传给 g_proxy_resolver_lookup 的 GError 出参槽由本端口用 koffi.free 自己负责释放。
        errorSlot = koffi.alloc('void *', 1)
      } catch {
        // 还没有分配任何 slot。
        settleOnce({ kind: 'error', code: -1, cleanupFailed: false })
        return
      }
      try {
        koffi.encode(errorSlot, 'void *', null)
      } catch {
        let cleanupFailed = false
        try {
          koffi.free(errorSlot)
        } catch {
          // 未能确认 slot 释放：必须与主失败一起上报，绝不静默。
          cleanupFailed = true
        }
        settleOnce({ kind: 'error', code: -1, cleanupFailed })
        return
      }
      const handleCompletion = (failure: unknown, result: unknown): void => {
        // 先收集，再判断：无论哪一步失败，其余原生资源都仍要被尝试释放。
        let cleanupFailed = false
        let first: string | null = null
        let hasResult = false
        if (result !== null && result !== undefined) {
          try {
            first = koffi.decode(result, 'char *') as string | null
            hasResult = true
          } catch {
            cleanupFailed = true
          }
          try {
            strfreev(result as bigint)
          } catch {
            cleanupFailed = true
          }
        }
        let nativeError: bigint | null = null
        try {
          nativeError = koffi.decode(errorSlot, 'void *') as bigint | null
        } catch {
          cleanupFailed = true
        }
        let code: number | null = null
        if (nativeError !== null) {
          try {
            const detail = koffi.decode(nativeError, errorStruct) as { code: number | bigint }
            code = Number(detail.code)
          } catch {
            cleanupFailed = true
          }
          try {
            errorFree(nativeError)
          } catch {
            cleanupFailed = true
          }
        }
        try {
          koffi.free(errorSlot)
        } catch {
          cleanupFailed = true
        }
        const dispatchFailed = failure !== null && failure !== undefined
        // 主失败（派发失败 / 没有候选 / 解码不到结果）与清理失败都要上报：清理失败不掩盖主失败，
        // 也不被主失败掩盖。
        if (dispatchFailed || !hasResult) {
          settleOnce({ kind: 'error', code: code ?? -1, cleanupFailed })
          return
        }
        settleOnce({ kind: 'proxies', first, cleanupFailed })
      }
      try {
        lookup.async(resolver, uri, cancellable, errorSlot, handleCompletion)
      } catch {
        // 同步启动就失败：已分配的 slot 也必须释放，释放是否被确认一并上报。
        let cleanupFailed = false
        try {
          koffi.free(errorSlot)
        } catch {
          cleanupFailed = true
        }
        settleOnce({ kind: 'error', code: -1, cleanupFailed })
      }
    },
  }
}
