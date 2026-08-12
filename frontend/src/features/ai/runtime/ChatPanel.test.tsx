import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatPanel } from '@/features/ai/runtime/ChatPanel'

const storageMocks = vi.hoisted(() => ({
  getBlobOriginalUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
}))

vi.mock('@/shared/api/storage-service', () => ({
  storageService: storageMocks,
}))

describe('ChatPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders thread transcript footer without sidebar or tool-approval UX', () => {
    render(
      <ChatPanel
        labels={{
          agentName: 'assistant',
          providerName: 'minimax',
          modelName: 'MiniMax',
          variantName: 'default',
        }}
        transcript={{
          timeline: {
            messages: [{ id: 'm1', role: 'user', text: 'hello', subjectEntryId: 'e1', createdAt: null }],
            queuedMessages: [],
            hasPendingInputs: false,
          },
          bodyRef: createRef<HTMLDivElement>(),
          loading: false,
          error: null,
        }}
        composer={{
          parts: [],
          pending: false,
          disabled: false,
          onPartsChange: vi.fn(),
          onSubmit: vi.fn(),
          onCommand: vi.fn(),
        }}
        footer={{ yoloEnabled: true, usage: undefined }}
        activity={{ working: false }}
      />,
    )
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText(/assistant/)).toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })

  it('treats a missing authoritative original response as an unavailable resource', async () => {
    storageMocks.getBlobOriginalUrl.mockRejectedValue(new Error('missing'))
    storageMocks.getBlobPreviewUrl.mockResolvedValue({
      url: 'https://s3.test/preview',
      expiresAt: '2026-08-12T00:00:00Z',
    })

    render(
      <ChatPanel
        labels={{ agentName: 'assistant' }}
        transcript={{
          timeline: {
            messages: [
              {
                id: 'm1',
                role: 'user',
                text: '',
                subjectEntryId: 'e1',
                createdAt: null,
                attachments: [
                  {
                    type: 'file',
                    name: 'image.png',
                    mime: '',
                    data: '',
                    blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
                  },
                ],
              },
            ],
            queuedMessages: [],
            hasPendingInputs: false,
          },
          bodyRef: createRef<HTMLDivElement>(),
          loading: false,
          error: null,
        }}
        composer={{
          parts: [],
          pending: false,
          disabled: false,
          onPartsChange: vi.fn(),
          onSubmit: vi.fn(),
          onCommand: vi.fn(),
        }}
        footer={{}}
        activity={{ working: false }}
      />,
    )

    expect(await screen.findByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /下载 image\.png/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'image.png' })).not.toBeInTheDocument()
  })

  it('renders the authoritative original inline when preview resolution fails', async () => {
    storageMocks.getBlobOriginalUrl.mockResolvedValue({
      url: 'https://s3.test/original',
      expiresAt: '2026-08-12T00:00:00Z',
      mediaType: 'image/png',
      sizeBytes: 64,
    })
    storageMocks.getBlobPreviewUrl.mockRejectedValue(new Error('no preview'))

    render(
      <ChatPanel
        labels={{ agentName: 'assistant' }}
        transcript={{
          timeline: {
            messages: [
              {
                id: 'm1',
                role: 'user',
                text: '',
                subjectEntryId: 'e1',
                createdAt: null,
                attachments: [
                  {
                    type: 'file',
                    name: 'image.png',
                    mime: '',
                    data: '',
                    blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
                  },
                ],
              },
            ],
            queuedMessages: [],
            hasPendingInputs: false,
          },
          bodyRef: createRef<HTMLDivElement>(),
          loading: false,
          error: null,
        }}
        composer={{
          parts: [],
          pending: false,
          disabled: false,
          onPartsChange: vi.fn(),
          onSubmit: vi.fn(),
          onCommand: vi.fn(),
        }}
        footer={{}}
        activity={{ working: false }}
      />,
    )

    expect(await screen.findByRole('img', { name: 'image.png' })).toHaveAttribute(
      'src',
      'https://s3.test/original',
    )
    expect(screen.getByRole('button', { name: '预览 image.png' })).toBeInTheDocument()
    expect(screen.queryByText('资源不可用')).not.toBeInTheDocument()
  })
})