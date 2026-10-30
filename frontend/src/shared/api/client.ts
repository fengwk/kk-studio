import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import { getLocale, translate } from '@/shared/i18n'
type JsonParser = (text: string) => unknown

interface RequestConfig {
  headers?: Record<string, string>
  jsonParser?: JsonParser
}

interface GetConfig extends RequestConfig {
  params?: Record<string, unknown>
}

interface MutationConfig extends RequestConfig {
  timeout?: number
}

interface DeleteConfig extends GetConfig {
  data?: unknown
}

export interface HttpClient {
  get<T>(url: string, config?: GetConfig): Promise<T>
  post<T>(url: string, data?: unknown, config?: MutationConfig): Promise<T>
  put<T>(url: string, data?: unknown, config?: MutationConfig): Promise<T>
  delete<T>(url: string, config?: DeleteConfig): Promise<T>
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

/** 只在本次请求 opt-in lossless parser；未指定时保留 Axios 默认 JSON transform。 */
function requestConfig(config?: GetConfig | MutationConfig | DeleteConfig): Record<string, unknown> | undefined {
  if (!config) {
    return undefined
  }
  const { jsonParser, ...rest } = config
  return jsonParser ? { ...rest, transformResponse: [jsonParser] } : rest
}

function isOrdinaryJsonData(data: unknown): data is Record<string, unknown> | unknown[] {
  if (Array.isArray(data)) {
    return true
  }
  if (data === null || typeof data !== 'object') {
    return false
  }
  const prototype = Object.getPrototypeOf(data)
  return prototype === Object.prototype || prototype === null
}

function jsonHeaders(headers?: Record<string, string>): Record<string, string> {
  const result = { ...(headers ?? {}) }
  if (!Object.keys(result).some((key) => key.toLowerCase() === 'content-type')) {
    result['Content-Type'] = 'application/json'
  }
  return result
}

/** 普通对象/数组在 Axios mergeConfig 之前原生序列化，保留调用方其它 headers。 */
function serializeBody(
  data: unknown,
  config?: MutationConfig | DeleteConfig,
): { data: unknown; config?: MutationConfig | DeleteConfig } {
  if (!isOrdinaryJsonData(data)) {
    return { data, config }
  }
  const serialized = JSON.stringify(data)
  if (serialized === undefined) {
    return { data, config }
  }
  return {
    data: serialized,
    config: { ...config, headers: jsonHeaders(config?.headers) },
  }
}

const axiosClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 60000,
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
  get: <T>(url: string, config?: GetConfig) => {
    const request = requestConfig(config)
    return (request === undefined
      ? axiosClient.get(url)
      : axiosClient.get(url, request)) as Promise<T>
  },
  post: <T>(url: string, data?: unknown, config?: MutationConfig) => {
    const body = serializeBody(data, config)
    const request = requestConfig(body.config)
    return (request === undefined
      ? axiosClient.post(url, body.data)
      : axiosClient.post(url, body.data, request)) as Promise<T>
  },
  put: <T>(url: string, data?: unknown, config?: MutationConfig) => {
    const body = serializeBody(data, config)
    const request = requestConfig(body.config)
    return (request === undefined
      ? axiosClient.put(url, body.data)
      : axiosClient.put(url, body.data, request)) as Promise<T>
  },
  delete: <T>(url: string, config?: DeleteConfig) => {
    const body = serializeBody(config?.data, config)
    const request = requestConfig(body.config)
    if (request === undefined) {
      return (body.data === undefined
        ? axiosClient.delete(url)
        : axiosClient.delete(url, { data: body.data })) as Promise<T>
    }
    return axiosClient.delete(
      url,
      body.data === undefined ? request : { ...request, data: body.data },
    ) as Promise<T>
  },
}
