import { describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  apiClient,
  isConflictError,
  isConflictReason,
  isNotFoundError,
} from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'

const axiosMock = vi.hoisted(() => {
  const client = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    interceptors: {
      request: {
        use: vi.fn(),
      },
      response: {
        use: vi.fn(),
      },
    },
  }
  return {
    client,
    create: vi.fn(() => client),
  }
})

vi.mock('axios', () => ({
  default: {
    create: axiosMock.create,
  },
}))

describe('apiClient', () => {
  it('adds the current locale to every request', () => {
    const [withLocale] = axiosMock.client.interceptors.request.use.mock.calls[0]

    setLocale('en-US')
    expect(withLocale({ headers: {} }).headers['Accept-Language']).toBe('en-US')

    setLocale('zh-CN')
    expect(withLocale({ headers: {} }).headers['Accept-Language']).toBe('zh-CN')
  })

  it('unwraps backend result envelopes and rejects failed envelopes', async () => {
    const [unwrap] = axiosMock.client.interceptors.response.use.mock.calls[0]

    expect(unwrap({ data: { status: 200, data: { ok: true } } })).toEqual({ ok: true })
    expect(unwrap({ data: { plain: true } })).toEqual({ plain: true })
    await expect(unwrap({ data: { status: 500, message: 'boom' } })).rejects.toThrow('boom')
    await expect(unwrap({ data: { status: 500 } })).rejects.toThrow('请求失败')
  })

  it('normalizes transport errors with backend, network and fallback messages', async () => {
    const [, normalizeError] = axiosMock.client.interceptors.response.use.mock.calls[0]

    await expect(normalizeError({ response: { data: { message: 'backend failed' } } })).rejects.toThrow('backend failed')
    await expect(normalizeError({ message: 'network failed' })).rejects.toThrow('network failed')
    await expect(normalizeError({})).rejects.toThrow('请求失败')
  })

  it('preserves backend code and error context without changing generic conflict handling', async () => {
    const [unwrap, normalizeError] = axiosMock.client.interceptors.response.use.mock.calls[0]

    let envelopeError: ApiError | undefined
    try {
      await unwrap({
        data: {
          status: 409,
          code: 'version_conflict',
          message: 'stale',
          errors: { resource: 'agent_model', expectedVersion: '2', actualVersion: '3' },
        },
      })
    } catch (error) {
      envelopeError = error as ApiError
    }
    expect(envelopeError).toMatchObject({
      status: 409,
      code: 'version_conflict',
      errors: { resource: 'agent_model', expectedVersion: '2', actualVersion: '3' },
    })
    expect(isConflictError(envelopeError)).toBe(true)

    let transportError: ApiError | undefined
    try {
      await normalizeError({
        response: {
          status: 409,
          data: { code: 'in_use', message: 'referenced', errors: { resource: 'agent_model' } },
        },
      })
    } catch (error) {
      transportError = error as ApiError
    }
    expect(transportError).toMatchObject({
      status: 409,
      code: 'in_use',
      errors: { resource: 'agent_model' },
    })
    expect(isConflictError(new ApiError('thread conflict', 409))).toBe(true)
    expect(
      isConflictReason(
        new ApiError('stale', 409, 'CONFLICT', { reason: 'STALE_COMMAND_CURSOR' }),
        'STALE_COMMAND_CURSOR',
      ),
    ).toBe(true)
    expect(
      isConflictReason(new ApiError('generic', 409, 'CONFLICT'), 'STALE_COMMAND_CURSOR'),
    ).toBe(false)
  })

  it('classifies not-found errors without matching other failures', () => {
    expect(isNotFoundError(new ApiError('missing', 404, 'NOT_FOUND'))).toBe(true)
    expect(isNotFoundError(new ApiError('failed', 500))).toBe(false)
    expect(isNotFoundError(new Error('missing'))).toBe(false)
  })

  it('delegates http requests to axios instance', async () => {
    axiosMock.client.get.mockResolvedValueOnce({ ok: true })
    axiosMock.client.post.mockResolvedValueOnce({ created: true })
    axiosMock.client.put.mockResolvedValueOnce({ updated: true })
    axiosMock.client.patch.mockResolvedValueOnce({ patched: true })
    axiosMock.client.delete.mockResolvedValueOnce(undefined)

    await expect(apiClient.get('/ai/catalog/agents', { params: { pageNumber: 1 } })).resolves.toEqual({ ok: true })
    await expect(apiClient.post('/ai/chat', { agentName: 'assistant' })).resolves.toEqual({ created: true })
    await expect(apiClient.put('/ai/catalog/agents/default-assistant', { name: 'Default' })).resolves.toEqual({ updated: true })
    await expect(apiClient.patch('/cloud/text', { path: '/a.txt' })).resolves.toEqual({ patched: true })
    await expect(apiClient.delete('/ai/catalog/agents/default-assistant')).resolves.toBeUndefined()

    expect(axiosMock.client.get).toHaveBeenCalledWith('/ai/catalog/agents', { params: { pageNumber: 1 } })
    expect(axiosMock.client.post).toHaveBeenCalledWith('/ai/chat', { agentName: 'assistant' })
    expect(axiosMock.client.put).toHaveBeenCalledWith('/ai/catalog/agents/default-assistant', { name: 'Default' })
    expect(axiosMock.client.patch).toHaveBeenCalledWith('/cloud/text', { path: '/a.txt' })
    expect(axiosMock.client.delete).toHaveBeenCalledWith('/ai/catalog/agents/default-assistant')
  })
})
