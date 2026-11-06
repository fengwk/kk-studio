import { API_RESPONSE_TYPES, type ApiRoutes } from './generated/contracts'
import { getLocale, translate } from '@/shared/i18n'

export type JsonParser = (text: string) => unknown

export interface RequestOptions {
  signal?: AbortSignal
  timeoutMs?: number
  jsonParser?: JsonParser
}

interface FetchRequestConfig {
  method?: string
  headers?: Record<string, string>
  body?: unknown
  signal?: AbortSignal
  timeoutMs?: number
  jsonParser?: JsonParser
  responseType?: 'json' | 'text' | 'blob'
  fetchImpl?: typeof fetch
  readResponse?: (response: Response) => Promise<unknown>
}

export class ApiError extends Error {
  readonly status?: number
  readonly code?: string
  readonly errors?: Record<string, unknown>
  readonly reason?: string
  readonly detail?: string

  constructor(
    message: string,
    status?: number,
    code?: string,
    errors?: Record<string, unknown>,
    reason?: string,
    detail?: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.errors = errors
    this.reason = reason
    this.detail = detail
  }
}

/** version 过期或 Thread 未处于静默状态；调用方必须先刷新再重试。 */
export function isConflictError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

/** 仅匹配后端显式暴露的稳定冲突分类；通用 409 不能据此自动重试。 */
export function isConflictReason(error: unknown, reason: string): boolean {
  return (
    error instanceof ApiError &&
    error.status === 409 &&
    error.reason === reason
  )
}

/** 请求的资源已不存在；调用方可以丢弃过期的本地引用。 */
export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

/** 后端回执中的去敏失败原因；没有可展示文本时返回 null。 */
export function apiErrorDetail(error: unknown): string | null {
  if (error instanceof ApiError) {
    if (typeof error.detail === 'string' && error.detail.length > 0) {
      return error.detail
    }
  }
  return null
}

function statusToFallbackCode(status: number): string {
  if (
    status === 400 ||
    status === 405 ||
    status === 406 ||
    status === 413 ||
    status === 415 ||
    status === 422
  ) {
    return 'validation'
  }
  if (status === 401 || status === 403) {
    return 'unauthorized'
  }
  if (status === 404) {
    return 'not_found'
  }
  if (status === 409) {
    return 'conflict'
  }
  if (status === 502 || status === 504) {
    return 'upstream_failed'
  }
  if (status === 503) {
    return 'unavailable'
  }
  return 'internal'
}

/** 解析路径模板中的单段参数与尾段通配参数。 */
function resolvePath(
  pathTemplate: string,
  pathParams?: Record<string, unknown>,
): string {
  return pathTemplate.replace(/\{(\*?)([^}]+)\}/g, (_match, isWildcard, paramName) => {
    const rawVal = pathParams?.[paramName]
    if (rawVal === undefined || rawVal === null) {
      throw new Error(`Missing path parameter: ${paramName}`)
    }
    const str = String(rawVal)
    if (isWildcard) {
      return str
        .split('/')
        .map((seg) => encodeURIComponent(seg))
        .join('/')
    }
    return encodeURIComponent(str)
  })
}

/** 将查询参数拼接到已有 URL 路径后。 */
function appendQueryParams(
  urlPath: string,
  queryParams?: Record<string, unknown>,
): string {
  if (!queryParams) {
    return urlPath
  }
  const searchParams = new URLSearchParams()
  for (const [key, val] of Object.entries(queryParams)) {
    if (val === undefined || val === null) {
      continue
    }
    if (Array.isArray(val)) {
      for (const item of val) {
        if (item !== undefined && item !== null) {
          searchParams.append(key, String(item))
        }
      }
    } else {
      searchParams.set(key, String(val))
    }
  }
  const queryString = searchParams.toString()
  if (!queryString) {
    return urlPath
  }
  const separator = urlPath.includes('?') ? '&' : '?'
  return `${urlPath}${separator}${queryString}`
}

/** 从形如 "METHOD /path" 的路由定义提取方法与模板。 */
function parseRoute(route: string): { method: string; pathTemplate: string } {
  const trimmed = route.trim()
  const spaceIdx = trimmed.indexOf(' ')
  if (spaceIdx === -1) {
    return { method: 'GET', pathTemplate: trimmed }
  }
  return {
    method: trimmed.slice(0, spaceIdx).toUpperCase(),
    pathTemplate: trimmed.slice(spaceIdx + 1).trim(),
  }
}

function isBinaryBody(body: unknown): boolean {
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    return true
  }
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return true
  }
  if (typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer) {
    return true
  }
  if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
    return true
  }
  return false
}

/**
 * 集中底层 fetch 执行器，供 request() 与下载等非路由表流（如 session 文件下载）复用。
 */
export async function executeFetch<T = unknown>(
  inputUrl: string,
  config: FetchRequestConfig = {},
): Promise<T> {
  const method = (config.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = {
    'Accept-Language': getLocale(),
    Accept: 'application/json, text/plain, */*',
    ...(config.headers ?? {}),
  }

  let bodyPayload: BodyInit | undefined
  if (config.body !== undefined) {
    if (typeof config.body === 'string' || isBinaryBody(config.body)) {
      bodyPayload = config.body as BodyInit
    } else {
      bodyPayload = JSON.stringify(config.body)
      if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
        headers['Content-Type'] = 'application/json'
      }
    }
  }

  const timeoutMs = config.timeoutMs !== undefined ? config.timeoutMs : 60000
  const controller = new AbortController()
  let isTimedOut = false
  let timer: ReturnType<typeof setTimeout> | undefined

  if (timeoutMs > 0) {
    timer = setTimeout(() => {
      isTimedOut = true
      controller.abort(new DOMException('Request timeout', 'TimeoutError'))
    }, timeoutMs)
  }

  const externalSignal = config.signal
  let abortListener: (() => void) | undefined
  if (externalSignal) {
    if (externalSignal.aborted) {
      if (timer !== undefined) {
        clearTimeout(timer)
      }
      throw externalSignal.reason ?? new DOMException('The operation was aborted', 'AbortError')
    }
    abortListener = () => {
      controller.abort(externalSignal.reason)
    }
    externalSignal.addEventListener('abort', abortListener, { once: true })
  }

  try {
    const response = await (config.fetchImpl ?? fetch)(inputUrl, {
      method,
      headers,
      body: bodyPayload,
      signal: controller.signal,
    })
    if (!response.ok) {
      throw await problemError(response)
    }
    if (config.readResponse) {
      return await config.readResponse(response) as T
    }
    return await readSuccess<T>(response, config)
  } catch (error: unknown) {
    if (isTimedOut) {
      throw new ApiError('Request timeout', undefined, 'timeout')
    }
    if (externalSignal?.aborted) {
      throw error
    }
    if (error instanceof ApiError) {
      throw error
    }
    const message = error instanceof Error ? error.message : translate('shared.requestFailed')
    throw new ApiError(message, undefined, 'network_error')
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer)
    }
    if (externalSignal && abortListener) {
      externalSignal.removeEventListener('abort', abortListener)
    }
  }
}

async function problemError(response: Response): Promise<ApiError> {
  let problem: Record<string, unknown> = {}
  if (response.headers.get('content-type')?.toLowerCase().split(';')[0]?.trim() === 'application/problem+json') {
    const text = await response.text()
    try {
      const value: unknown = JSON.parse(text)
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        problem = value as Record<string, unknown>
      }
    } catch {
      // 格式错误时使用稳定的 HTTP 回退。
    }
  }
  const errors = problem.errors
  return new ApiError(
    typeof problem.title === 'string' ? problem.title : translate('shared.requestFailed'),
    response.status,
    typeof problem.code === 'string' ? problem.code : statusToFallbackCode(response.status),
    errors && typeof errors === 'object' && !Array.isArray(errors) ? errors as Record<string, unknown> : undefined,
    typeof problem.reason === 'string' ? problem.reason : undefined,
    typeof problem.detail === 'string' ? problem.detail : undefined,
  )
}

async function readSuccess<T>(response: Response, config: FetchRequestConfig): Promise<T> {
  if (response.status === 204 || response.status === 205 || response.headers.get('content-length') === '0') {
    return undefined as T
  }

  const contentType = response.headers.get('content-type') ?? ''
  const responseType = config.responseType

  if (responseType === 'blob') {
    const blob = await response.blob()
    return (blob.size === 0 ? undefined : blob) as T
  }
  if (responseType === 'text') {
    const text = await response.text()
    return (text.length === 0 ? undefined : text) as T
  }

  const isJson = contentType.includes('application/json') || contentType.includes('+json')
  if (isJson || (!contentType && !responseType)) {
    const rawText = await response.text()
    if (rawText.trim().length === 0) {
      return undefined as T
    }
    if (config.jsonParser) {
      return config.jsonParser(rawText) as T
    }
    return JSON.parse(rawText) as T
  }

  if (contentType.startsWith('text/')) {
    const text = await response.text()
    return (text.length === 0 ? undefined : text) as T
  }

  return (await response.blob()) as T
}

type QueryArgs<T> = T extends { query: infer Q }
  ? Record<never, never> extends Q ? { query?: Q } : { query: Q }
  : Record<never, never>
export type RouteArgs<R extends keyof ApiRoutes> =
  Pick<ApiRoutes[R], Extract<keyof ApiRoutes[R], 'path' | 'body'>> & QueryArgs<ApiRoutes[R]>
export type RouteResponse<R extends keyof ApiRoutes> = ApiRoutes[R]['response']

/**
 * 构造类型化路由的完整相对 URL（包含路径参数替换与查询参数拼接）。
 */
export function url<R extends keyof ApiRoutes>(route: R, args: RouteArgs<R>): string {
  const { pathTemplate } = parseRoute(route)
  const params = args as { path?: Record<string, unknown>; query?: Record<string, unknown> }
  return appendQueryParams(resolvePath(pathTemplate, params.path), params.query)
}

/**
 * 前端核心类型化 fetch 请求。
 */
export function request<R extends keyof ApiRoutes>(
  route: R,
  args: RouteArgs<R>,
  options?: RequestOptions,
): Promise<RouteResponse<R>> {
  const { method } = parseRoute(route)
  const params = args as { body?: unknown }
  return executeFetch<RouteResponse<R>>(url(route, args), {
    method,
    body: params.body,
    signal: options?.signal,
    timeoutMs: options?.timeoutMs,
    jsonParser: options?.jsonParser,
    responseType: API_RESPONSE_TYPES[route],
  })
}
