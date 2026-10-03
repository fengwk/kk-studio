import axios, { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter } from 'axios'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'

const adapter = vi.fn<AxiosAdapter>()
let apiClient: HttpClient

beforeAll(async () => {
  // 仅替换传输边界，保留升级后的真实 Axios 请求/响应转换和拦截器管线。
  const originalAdapter = axios.defaults.adapter
  axios.defaults.adapter = adapter
  try {
    apiClient = (await import('@/shared/api/client')).apiClient
  } finally {
    axios.defaults.adapter = originalAdapter
  }
})

afterEach(() => {
  adapter.mockReset()
  setLocale('zh-CN')
})

describe('apiClient with real axios', () => {
  it('preserves request defaults, locale, custom headers and JSON envelopes', async () => {
    // Axios 升级不能改变 API 基址、超时、语言头或信封解包行为。
    setLocale('en-US')
    adapter.mockImplementation(async (config) => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      data: JSON.stringify({ status: 200, data: { ok: true } }),
    }))

    await expect(apiClient.get('/catalog', {
      params: { pageNumber: 1 },
      headers: { 'X-Request-Id': 'regression' },
    })).resolves.toEqual({ ok: true })

    const [config] = adapter.mock.calls[0]
    expect(config).toMatchObject({
      method: 'get',
      baseURL: '/api',
      timeout: 60000,
      params: { pageNumber: 1 },
    })
    expect(config.headers.get('Accept-Language')).toBe('en-US')
    expect(config.headers.get('X-Request-Id')).toBe('regression')
  })

  it('serializes mutation bodies and rejects failed application envelopes', async () => {
    // 真实 JSON 序列化及 HTTP 200 中的业务失败必须保持不变。
    adapter.mockImplementation(async (config) => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      data: JSON.stringify({ status: 409, code: 'CONFLICT', message: 'stale' }),
    }))

    await expect(apiClient.post('/catalog', { name: 'example' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 'CONFLICT',
      message: 'stale',
    })
    const [config] = adapter.mock.calls[0]
    expect(config.data).toBe('{"name":"example"}')
    expect(config.headers.get('Content-Type')).toBe('application/json')
  })

  it('transforms HTTP error bodies before normalizing backend error context', async () => {
    // 适配器按 Axios 契约拒绝非 2xx；错误响应也必须先 JSON 解码，再保留后端上下文。
    adapter.mockImplementation(async (config) => {
      throw new AxiosError('Request failed with status code 404', 'ERR_BAD_REQUEST', config, undefined, {
        config,
        status: 404,
        statusText: 'Not Found',
        headers: new AxiosHeaders(),
        data: JSON.stringify({
          status: 404,
          code: 'NOT_FOUND',
          message: 'missing',
          errors: { resource: 'catalog' },
        }),
      })
    })

    await expect(apiClient.delete('/catalog/missing')).rejects.toMatchObject({
      name: 'ApiError',
      status: 404,
      code: 'NOT_FOUND',
      message: 'missing',
      errors: { resource: 'catalog' },
    })
  })
})
