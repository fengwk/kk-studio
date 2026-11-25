import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TurnCutDialog } from '@/features/thread/workspace/TurnCutDialog'
import { ApiError, request } from '@/shared/api/client'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const mockedRequest = vi.mocked(request)

function renderDialog(kind: 'branch' | 'fork', props: Partial<Parameters<typeof TurnCutDialog>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onCreated = vi.fn()
  const onClose = vi.fn()
  const result = render(
    <QueryClientProvider client={queryClient}>
      <TurnCutDialog
        kind={kind}
        initialName={kind === 'branch' ? '分支 1' : '会话 · 分叉'}
        spaceId="space-1"
        sessionId="sess-1"
        sourceThreadId="thread-1"
        turnEndEntryId="entry-1"
        onCreated={onCreated}
        onClose={onClose}
        {...props}
      />
    </QueryClientProvider>,
  )
  return { ...result, onCreated, onClose }
}

describe('TurnCutDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('creates a branch, handles name conflict, resets error on input, and succeeds on retry', async () => {
    const user = userEvent.setup()
    mockedRequest
      .mockRejectedValueOnce(new ApiError('exists', 409, 'THREAD_NAME_EXISTS', undefined, 'THREAD_NAME_EXISTS'))
      .mockResolvedValueOnce({ sessionId: 'sess-1', threadId: 'branch-2' })

    const { onCreated } = renderDialog('branch')
    expect(screen.getByRole('heading', { name: '从此处分支' })).toBeInTheDocument()

    const input = screen.getByLabelText('分支名称') as HTMLInputElement
    expect(input.value).toBe('分支 1')

    await user.click(screen.getByRole('button', { name: '创建' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('此会话已有同名 Thread 或草稿，请换一个名称。')

    await user.clear(input)
    // Empty name disables submit
    expect(screen.getByRole('button', { name: '创建' })).toBeDisabled()
    expect(screen.queryByRole('alert')).toBeNull()

    await user.type(input, '  新分支  ')
    await user.click(screen.getByRole('button', { name: '创建' }))
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith('branch-2'))
    expect(mockedRequest).toHaveBeenLastCalledWith(
      'POST /api/chat-spaces/{spaceId}/sessions/{id}/branches',
      {
        path: { spaceId: 'space-1', id: 'sess-1' },
        body: { title: '新分支', sourceThreadId: 'thread-1', turnEndEntryId: 'entry-1' },
      },
      { idempotencyKey: expect.any(String) },
    )
  })

  it('forks to a new session, shows generic error, and closes on cancel', async () => {
    const user = userEvent.setup()
    mockedRequest.mockRejectedValueOnce(new Error('server error'))

    const { onClose } = renderDialog('fork')
    expect(screen.getByRole('heading', { name: '从此处新建会话' })).toBeInTheDocument()
    expect(screen.getByLabelText('会话名称')).toHaveValue('会话 · 分叉')

    await user.click(screen.getByRole('button', { name: '创建' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('server error')
    expect(mockedRequest).toHaveBeenCalledWith(
      'POST /api/chat-spaces/{spaceId}/sessions/{id}/forks',
      expect.any(Object),
      { idempotencyKey: expect.any(String) },
    )

    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
