import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import { getLocale, translate } from '@/shared/i18n'
import {
  parseJsonPreservingPathEqualsNumbers,
  stringifyJsonPreservingNumbers,
} from '@/shared/lib/lossless-json-number'

export interface HttpClient {
  get<T>(url: string, config?: { params?: Record<string, unknown>; headers?: Record<string, string> }): Promise<T>
  post<T>(url: string, data?: unknown, config?: { timeout?: number }): Promise<T>
  put<T>(url: string, data?: unknown, config?: { timeout?: number }): Promise<T>
  delete<T>(url: string, config?: { params?: Record<string, unknown>; headers?: Record<string, string>; data?: unknown }): Promise<T>
}

export const apiBaseUrl = '/api'

/** 携带后端 HTTP 状态的传输/信封失败，供调用方按场景恢复。 */
export class ApiError extends Error {
  readonly status?: number
  readonly code?: string
  readonly errors?: Record<string, unknown>

  constructor(message: string, status?: number, code?: string, errors?: Record<string, unknown>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.errors = errors
  }
}

/** version 过期或 Thread 未处于静默状态；调用方必须先刷新再重试。 */
export function isConflictError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

/** 仅匹配后端显式暴露的稳定冲突分类；通用 409 不能据此自动重试。 */
export function isConflictReason(error: unknown, reason: string): boolean {
  return error instanceof ApiError && error.status === 409 && error.errors?.reason === reason
}

/** 请求的资源已不存在；调用方可以丢弃过期的本地引用。 */
export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

/**
 * 解析响应时保留 request-body 定价条件中 JSON number 的原文。
 *
 * <p>只处理对象/数组 JSON 文本；其它响应形态原样交给调用方。命中数字条件但运行时缺少 `JSON.parse` source access 时显式失败，
 * 不把已舍入的 number 交给 draft。
 */
function parseJsonResponse(data: unknown): unknown {
  if (typeof data !== 'string') {
    return data
  }
  const head = data.trimStart().charAt(0)
  if (head !== '{' && head !== '[') {
    return data
  }
  return parseJsonPreservingPathEqualsNumbers(data)
}

/** 只接管 JSON 对象/数组；FormData、Blob、字符串等保持 Axios 原有传输形态。 */
function serializeJsonRequest(
  data: unknown,
  headers?: { setContentType?: (value: string, rewrite?: boolean) => void },
): unknown {
  if (data === null || typeof data !== 'object') {
    return data
  }
  const prototype = Object.getPrototypeOf(data)
  if (!Array.isArray(data) && prototype !== Object.prototype && prototype !== null) {
    return data
  }
  headers?.setContentType?.('application/json', false)
  return stringifyJsonPreservingNumbers(data)
}

const axiosClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 60000,
  transformRequest: [serializeJsonRequest],
  transformResponse: [parseJsonResponse],
})

axiosClient.interceptors.request.use((config) => {
  const locale = getLocale()
  if (typeof config.headers?.set === 'function') {
    config.headers.set('Accept-Language', locale)
  } else {
    const headers = config.headers ?? {}
    Object.assign(headers, { 'Accept-Language': locale })
    config.headers = headers as unknown as typeof config.headers
  }
  return config
})

axiosClient.interceptors.response.use(
  (response) => {
    const envelope = response.data as ResultEnvelope<unknown>
    if (!envelope || typeof envelope.status !== 'number') {
      return response.data
    }
    if (envelope.status < 200 || envelope.status >= 300) {
      return Promise.reject(
        new ApiError(
          envelope.message || translate('shared.requestFailed'),
          envelope.status,
          envelope.code,
          envelope.errors ?? undefined,
        ),
      )
    }
    return envelope.data
  },
  (error) => {
    const envelope = error?.response?.data as Partial<ResultEnvelope<unknown>> | undefined
    const message = envelope?.message || error?.message || translate('shared.requestFailed')
    return Promise.reject(
      new ApiError(message, error?.response?.status, envelope?.code, envelope?.errors ?? undefined),
    )
  },
)

export const apiClient: HttpClient = {
  get: <T>(url: string, config?: { params?: Record<string, unknown>; headers?: Record<string, string> }) =>
    axiosClient.get(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown, config?: { timeout?: number }) =>
    (config ? axiosClient.post(url, data, config) : axiosClient.post(url, data)) as Promise<T>,
  put: <T>(url: string, data?: unknown, config?: { timeout?: number }) =>
    (config ? axiosClient.put(url, data, config) : axiosClient.put(url, data)) as Promise<T>,
  delete: <T>(url: string, config?: { params?: Record<string, unknown>; headers?: Record<string, string> }) =>
    (config ? axiosClient.delete(url, config) : axiosClient.delete(url)) as Promise<T>,
}
