import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatPanel } from '@/features/ai/runtime/ChatPanel'

const storageMocks = vi.hoisted(() => ({
  getBlobDownloadUrl: vi.fn(),
  getBlobPreviewUrl: vi.fn(),
}))

vi.mock('@/shared/api/storage-service', () => ({
  storageService: storageMocks,
}))

describe('ChatPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders transcript and readonly branch facts without sidebar or tool-approval UX', () => {
    render(
      <ChatPanel
        labels={{
          environment: { environmentId: 'env-1', environmentName: 'local' },
          environmentReady: true,
          branchUsage: {
            input: 10,
            output: 2,
            cacheRead: 0,
            cacheWrite: 0,
            reasoning: 0,
            providerTotal: 12,
            cost: 0.125,
          },
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
        activity={{ working: false }}
      />,
    )
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText('env:local')).toBeInTheDocument()
    expect(screen.getByText('↑10 · ↓2 · $0.125 · cache 0% · — tok/s')).toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })

  it('keeps working visible while an interaction panel hides Composer, queue, and widgets', () => {
    const { container } = render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [],
            queuedMessages: [
              {
                idempotencyKey: 'queued-1',
                role: 'user',
                text: 'queued input',
                sequence: 1,
              },
            ],
            hasPendingInputs: true,
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
          interactionPanel: <section aria-label="Inline picker">picker</section>,
        }}
        activity={{
          working: true,
          widgets: <div>task widget</div>,
        }}
      />,
    )

    // Interaction mode keeps only the global working signal; queue/widgets and input are mutually exclusive.
    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.queryByText('queued input')).not.toBeInTheDocument()
    expect(screen.queryByText('task widget')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Inline picker')).toBeInTheDocument()
    expect(container.querySelector('.thread-composer')).toHaveAttribute('hidden')
    expect(screen.getByLabelText('会话状态')).toHaveTextContent('未选择环境')
  })

  it('renders localized WAITING_CHILDREN status label alongside interaction panel without interference', () => {
    // 测试意图：验证当线程处于 WAITING_CHILDREN 状态且交互面板（如问答/审批 WAITING_INPUT）打开时，
    // 状态条依然展示“等待子线程”，与 interactionPanel 和谐共存，Composer 保持收起，确保交叉语义正确。
    const waitingLabel = '等待子线程'
    const { container } = render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [],
            queuedMessages: [{ idempotencyKey: 'q-1', role: 'user', text: 'queued input', sequence: 1 }],
            hasPendingInputs: true,
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
          interactionPanel: <section aria-label="Questionnaire Interaction">ask_user questionnaire</section>,
        }}
        activity={{
          working: true,
          workingLabel: waitingLabel,
          widgets: <div>sub-widget</div>,
        }}
      />,
    )

    expect(screen.getByText('等待子线程')).toBeInTheDocument()
    expect(screen.getByLabelText('Questionnaire Interaction')).toBeInTheDocument()
    expect(screen.queryByText('sub-widget')).not.toBeInTheDocument()
    expect(screen.queryByText('queued input')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-composer')).toHaveAttribute('hidden')
  })

  it('renders localized QUEUED status label alongside interaction panel without interference', () => {
    // 测试意图：验证当线程处于 QUEUED 排队状态且交互面板激活时，正确展示“排队中”且隐藏输入框与常规队列。
    const queuedLabel = '排队中'
    const { container } = render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [],
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
          interactionPanel: <section aria-label="Approval Interaction">tool approval</section>,
        }}
        activity={{
          working: true,
          workingLabel: queuedLabel,
        }}
      />,
    )

    expect(screen.getByText('排队中')).toBeInTheDocument()
    expect(screen.getByLabelText('Approval Interaction')).toBeInTheDocument()
    expect(container.querySelector('.thread-composer')).toHaveAttribute('hidden')
  })

  it('treats a missing authoritative original response as an unavailable resource', async () => {
    storageMocks.getBlobDownloadUrl.mockRejectedValue(new Error('missing'))
    storageMocks.getBlobPreviewUrl.mockResolvedValue({
      url: 'https://s3.test/preview',
      expiresAt: '2026-08-12T00:00:00Z',
    })

    render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [
              {
                id: 'm1',
                role: 'user',
                text: '',
                subjectEntryId: 'e1',
                createdAt: null,
                contents: [
                  {
                    type: 'resource',
                    attachment: {
                      type: 'file',
                      name: 'image.png',
                      mime: '',
                      data: '',
                      blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
                    },
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
        activity={{ working: false }}
      />,
    )

    expect(await screen.findByText('资源不可用')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /下载 image\.png/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'image.png' })).not.toBeInTheDocument()
  })

  it('renders the authoritative original inline when preview resolution fails', async () => {
    storageMocks.getBlobDownloadUrl.mockResolvedValue({
      url: 'https://s3.test/original',
      expiresAt: '2026-08-12T00:00:00Z',
      mediaType: 'image/png',
      sizeBytes: 64,
    })
    storageMocks.getBlobPreviewUrl.mockRejectedValue(new Error('no preview'))

    render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [
              {
                id: 'm1',
                role: 'user',
                text: '',
                subjectEntryId: 'e1',
                createdAt: null,
                contents: [
                  {
                    type: 'resource',
                    attachment: {
                      type: 'file',
                      name: 'image.png',
                      mime: '',
                      data: '',
                      blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
                    },
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

  it('renders the debug main view exclusively: transcript is unmounted, composer/queue stay', () => {
    const { container } = render(
      <ChatPanel
        labels={{}}
        transcript={{
          timeline: {
            messages: [{ id: 'm1', role: 'user', text: 'conversation text', subjectEntryId: 'e1', createdAt: null }],
            queuedMessages: [
              {
                idempotencyKey: 'queued-1',
                role: 'user',
                text: 'queued input',
                sequence: 1,
              },
            ],
            hasPendingInputs: true,
          },
          bodyRef: createRef<HTMLDivElement>(),
          loading: false,
          error: null,
        }}
        mainView={{
          debug: (
            <div role="listbox" aria-label="事件">
              <div role="option">entry event</div>
            </div>
          ),
        }}
        composer={{
          parts: [],
          pending: false,
          disabled: false,
          onPartsChange: vi.fn(),
          onSubmit: vi.fn(),
          onCommand: vi.fn(),
        }}
        activity={{ working: true }}
      />,
    )

    // Conversation 与 Event 只渲染一个主滚动区：transcript 被替换。
    expect(screen.getByRole('listbox', { name: '事件' })).toBeInTheDocument()
    expect(screen.queryByText('conversation text')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-dialogue')).toBeNull()
    // Composer 与 queue/working 保持挂载（切换不丢状态）。
    expect(container.querySelector('.thread-composer')).not.toBeNull()
    expect(screen.getByText('queued input')).toBeInTheDocument()
    expect(screen.getByText('Working...')).toBeInTheDocument()
  })
})