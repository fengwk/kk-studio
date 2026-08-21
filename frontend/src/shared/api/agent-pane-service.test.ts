import { describe, expect, it, vi } from 'vitest'
import { createAgentPaneService } from '@/shared/api/agent-pane-service'
import type { HttpClient } from '@/shared/api/client'

function client(): HttpClient & {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
} {
  return {
    get: vi.fn().mockResolvedValue([]),
    post: vi.fn().mockResolvedValue({}),
    put: vi.fn(),
    delete: vi.fn(),
  }
}

describe('agent-pane wire service', () => {
  it('uses the single command-batches endpoint for all owners and targets', async () => {
    const http = client()
    const service = createAgentPaneService(http)
    const request = {
      owner: { type: 'CANVAS' as const, id: 'canvas-1' },
      target: {
        type: 'NEW_SESSION' as const,
        sessionId: 's1',
        threadId: 't1',
        rootSettings: {
          environment: null,
          agentName: 'assistant',
          model: { providerName: 'p', modelName: 'm', variant: 'v' },
          activeTools: [],
        },
        yoloEnabled: false,
      },
      commands: [{
        type: 'USER_MESSAGE' as const,
        clientCommandId: 'c1',
        contents: [{ type: 'TEXT' as const, text: 'hello' }],
      }],
    }
    await service.acceptCommandBatch(request)
    expect(http.post).toHaveBeenCalledWith('/ai/runtime/command-batches', request)
  })

  it('keeps session, thread, entries and compact routes separate from graph APIs', async () => {
    const http = client()
    const service = createAgentPaneService(http)
    await service.listChatSessions('chat /1')
    await service.listCanvasSessions('canvas /1')
    await service.listSessionThreads('session /1')
    await service.listSessionEntries('session /1')
    await service.getThreadSnapshot('thread /1')
    await service.compactThread('thread /1', { expectedVersion: '4' })
    expect(http.get.mock.calls.map(([path]) => path)).toEqual([
      '/ai/chat/chat%20%2F1/sessions',
      '/canvases/canvas%20%2F1/sessions',
      '/ai/runtime/sessions/session%20%2F1/threads',
      '/ai/runtime/sessions/session%20%2F1/entries',
      '/ai/runtime/threads/thread%20%2F1/snapshot',
    ])
    expect(http.post).toHaveBeenLastCalledWith(
      '/ai/runtime/threads/thread%20%2F1/compact',
      { expectedVersion: '4' },
    )
  })
})
