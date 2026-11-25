import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadPane } from '@/features/thread/runtime/panes/ThreadPane'
import type { ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline/thread-timeline'
import { request } from '@/shared/api/client'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)
const SESSION_ID = '11111111-2222-3333-4444-555555555555'
const BLOB_ID = '22222222-3333-4444-5555-666666666666'
const THREAD_ID = '33333333-4444-5555-6666-777777777777'

describe('ThreadPane session blob URL resolution', () => {
  beforeEach(() => {
    setLocale('zh-CN')
    apiRequest.mockReset()
  })

  it('signs session attachments via the projection sessionId', async () => {
    apiRequest
      .mockResolvedValueOnce({
        method: 'GET',
        url: 'https://s3.test/orig.png',
        headers: {},
        expiresAt: 1786492800000,
        mediaType: 'image/png',
        sizeBytes: '42',
      })
      .mockResolvedValueOnce({
        method: 'GET',
        url: 'https://s3.test/prev.webp',
        headers: {},
        expiresAt: 1786492800000,
        mediaType: null,
        sizeBytes: null,
      })

    const timeline = buildThreadTimeline([
      {
        entryId: 'entry-1',
        sessionId: SESSION_ID,
        parentEntryId: null,
        entryType: 'CUSTOM_MESSAGE',
        payload: {
          message: {
            role: 'USER',
            contents: [{
              type: 'resource',
              blobId: BLOB_ID,
              name: 'diagram.png',
              mediaType: 'image/png',
            }],
          },
        },
        createdAt: 1774958400000,
        usageCost: null,
      },
    ], [], [], null, new Map(), [], null)

    const projection = {
      threadId: THREAD_ID,
      sessionId: SESSION_ID,
      models: [],
      thread: undefined,
      bound: false,
      unavailable: false,
      isRoot: true,
      title: THREAD_ID,
      timeline,
      events: [],
      runtimeLabels: { environmentName: null, contextWindow: undefined },
      branchUsage: null,
      working: false,
      entries: [],
      messagesLoading: false,
      messagesError: null,
      retrySnapshotSync: vi.fn(),
      bodyRef: { current: null },
      queuedCommands: [],
      queuedCustomMessages: [],
      stopReceipts: [],
      unconfirmedExecutions: [],
      activeAssistantOutput: null,
      toolInvocations: [],
      generationFailures: [],
      snapshotQuery: {} as ThreadProjection['snapshotQuery'],
    } as unknown as ThreadProjection

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <ThreadPane
          projection={projection}
          environments={[]}
          activity={{}}
          views={{ initialConversationScrollTop: null }}
        />
      </QueryClientProvider>,
    )

    const image = await screen.findByRole('img', { name: 'diagram.png' })
    expect(image).toHaveAttribute('src', 'https://s3.test/prev.webp')
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/download-url',
      { path: { sessionId: SESSION_ID, blobId: BLOB_ID } },
      { signal: undefined },
    )
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/preview-url',
      { path: { sessionId: SESSION_ID, blobId: BLOB_ID } },
      { signal: undefined },
    )
  })
})
