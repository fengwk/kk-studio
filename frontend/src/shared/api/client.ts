import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts'

export interface HttpClient {
  get<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
  post<T>(url: string, data?: unknown): Promise<T>
  put<T>(url: string, data?: unknown): Promise<T>
  delete<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
}

export const apiBaseUrl = '/api'

/** Transport/envelope failure carrying the backend HTTP status so callers can branch on 409. */
export class ApiError extends Error {
  readonly status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Stale executionEpoch or a non-quiescent Thread; the caller must refresh before retrying. */
export function isConflictError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409
}

const axiosClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 60000,
})

axiosClient.interceptors.response.use(
  (response) => {
    const envelope = response.data as ResultEnvelope<unknown>
    if (!envelope || typeof envelope.status !== 'number') {
      return response.data
    }
    if (envelope.status < 200 || envelope.status >= 300) {
      return Promise.reject(new ApiError(envelope.message || '请求失败', envelope.status))
    }
    return envelope.data
  },
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new ApiError(message, error?.response?.status))
  },
)

export const apiClient: HttpClient = {
  get: <T>(url: string, config?: { params?: Record<string, unknown> }) => axiosClient.get(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown) => axiosClient.post(url, data) as Promise<T>,
  put: <T>(url: string, data?: unknown) => axiosClient.put(url, data) as Promise<T>,
  delete: <T>(url: string, config?: { params?: Record<string, unknown> }) =>
    (config ? axiosClient.delete(url, config) : axiosClient.delete(url)) as Promise<T>,
}
