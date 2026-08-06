import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import { getLocale, translate } from '@/shared/i18n'

export interface HttpClient {
  get<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
  post<T>(url: string, data?: unknown): Promise<T>
  put<T>(url: string, data?: unknown): Promise<T>
  delete<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
}

export const apiBaseUrl = '/api'

/** Transport/envelope failure carrying the backend HTTP status for caller-specific recovery. */
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

/** Stale revision or a non-quiescent Thread; the caller must refresh before retrying. */
export function isConflictError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

/** The requested resource no longer exists; callers may discard stale local references. */
export function isNotFoundError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
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
          envelope.errors,
        ),
      )
    }
    return envelope.data
  },
  (error) => {
    const envelope = error?.response?.data as Partial<ResultEnvelope<unknown>> | undefined
    const message = envelope?.message || error?.message || translate('shared.requestFailed')
    return Promise.reject(
      new ApiError(message, error?.response?.status, envelope?.code, envelope?.errors),
    )
  },
)

export const apiClient: HttpClient = {
  get: <T>(url: string, config?: { params?: Record<string, unknown> }) => axiosClient.get(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown) => axiosClient.post(url, data) as Promise<T>,
  put: <T>(url: string, data?: unknown) => axiosClient.put(url, data) as Promise<T>,
  delete: <T>(url: string, config?: { params?: Record<string, unknown> }) =>
    (config ? axiosClient.delete(url, config) : axiosClient.delete(url)) as Promise<T>,
}
