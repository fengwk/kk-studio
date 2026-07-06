import { describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'

const axiosMock = vi.hoisted(() => {
  const client = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    interceptors: {
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

  it('delegates http requests to axios instance', async () => {
    axiosMock.client.get.mockResolvedValueOnce({ ok: true })
    axiosMock.client.post.mockResolvedValueOnce({ created: true })
    axiosMock.client.put.mockResolvedValueOnce({ updated: true })
    axiosMock.client.delete.mockResolvedValueOnce(undefined)

    await expect(apiClient.get('/agent/agents', { params: { pageNumber: 1 } })).resolves.toEqual({ ok: true })
    await expect(apiClient.post('/agent/sessions', { agentName: 'default-assistant' })).resolves.toEqual({ created: true })
    await expect(apiClient.put('/agent/agents/default-assistant', { name: 'Default' })).resolves.toEqual({ updated: true })
    await expect(apiClient.delete('/agent/agents/default-assistant')).resolves.toBeUndefined()

    expect(axiosMock.client.get).toHaveBeenCalledWith('/agent/agents', { params: { pageNumber: 1 } })
    expect(axiosMock.client.post).toHaveBeenCalledWith('/agent/sessions', { agentName: 'default-assistant' })
    expect(axiosMock.client.put).toHaveBeenCalledWith('/agent/agents/default-assistant', { name: 'Default' })
    expect(axiosMock.client.delete).toHaveBeenCalledWith('/agent/agents/default-assistant')
  })
})
