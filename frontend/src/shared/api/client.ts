import axios from 'axios'
import type { ResultEnvelope } from '@/shared/api/contracts'

export interface HttpClient {
  get<T>(url: string, config?: { params?: Record<string, unknown> }): Promise<T>
  post<T>(url: string, data?: unknown): Promise<T>
  put<T>(url: string, data?: unknown): Promise<T>
  delete<T>(url: string): Promise<T>
}

export const apiBaseUrl = '/api'

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
      return Promise.reject(new Error(envelope.message || '请求失败'))
    }
    return envelope.data
  },
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  },
)

export const apiClient: HttpClient = {
  get: <T>(url: string, config?: { params?: Record<string, unknown> }) => axiosClient.get(url, config) as Promise<T>,
  post: <T>(url: string, data?: unknown) => axiosClient.post(url, data) as Promise<T>,
  put: <T>(url: string, data?: unknown) => axiosClient.put(url, data) as Promise<T>,
  delete: <T>(url: string) => axiosClient.delete(url) as Promise<T>,
}
