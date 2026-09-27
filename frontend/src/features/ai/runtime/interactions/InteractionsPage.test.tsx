import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { interactionService } from '@/shared/api/interaction-service'
import { projectsApi } from '@/features/projects/projects-api'
import type { IssueDetailDTO } from '@/features/projects/types'
import { InteractionsPage } from './InteractionsPage'

const mockedNavigate = vi.fn()
vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockedNavigate,
  }
})

describe('InteractionsPage', () => {
  let queryClient: QueryClient

  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </MemoryRouter>
  )

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    mockedNavigate.mockReset()
    vi.restoreAllMocks()
  })

  it('renders empty state when there are no interactions', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [],
      nextCursor: null,
    })

    render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByText('暂无待处理项')).toBeInTheDocument()
    })
  })

  it('renders error state and retries on button click', async () => {
    let callCount = 0
    const listSpy = vi.spyOn(interactionService, 'listInteractions').mockImplementation(async () => {
      callCount++
      if (callCount === 1) {
        throw new Error('Network disconnected')
      }
      return { items: [], nextCursor: null }
    })

    render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByText('Network disconnected')).toBeInTheDocument()
    })

    const retryBtn = screen.getByRole('button', { name: '重试' })
    fireEvent.click(retryBtn)

    await waitFor(() => {
      expect(screen.getByText('暂无待处理项')).toBeInTheDocument()
    })
    expect(listSpy).toHaveBeenCalled()
  })

  it('renders items with Chat and Issue sources and supports navigation', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          interactionId: 'int-1',
          status: 'WAITING_INPUT',
          threadId: 'th-1',
          sessionId: 'sess-1',
          owner: { type: 'CHAT', chatId: 'chat-abc', issueId: null, agentName: null },
          toolCallId: 'call-1',
          toolName: 'ask_user',
          argumentsJson: JSON.stringify({
            questions: [{ question: '你选择哪个？', options: [{ label: '选项A' }] }],
          }),
          approvalJson: null,
          createTime: '2026-09-27T10:00:00Z',
        },
        {
          interactionId: 'int-2',
          status: 'WAITING_APPROVAL',
          threadId: 'th-2',
          sessionId: 'sess-2',
          owner: { type: 'ISSUE_AGENT', chatId: null, issueId: 'issue-101', agentName: 'architect' },
          toolCallId: 'call-2',
          toolName: 'write',
          argumentsJson: '{"path":"main.ts"}',
          approvalJson: JSON.stringify({ required: true, reason: '写代码' }),
          createTime: '2026-09-27T10:05:00Z',
        },
      ],
      nextCursor: null,
    })

    render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByText('等待输入')).toBeInTheDocument()
      expect(screen.getByText('等待审批')).toBeInTheDocument()
    })

    // 验证问卷内容存在
    expect(screen.getByText('你选择哪个？')).toBeInTheDocument()
    expect(screen.getByText('选项A')).toBeInTheDocument()

    // 验证审批内容存在
    expect(screen.getByText('write')).toBeInTheDocument()
    expect(screen.getByText('写代码')).toBeInTheDocument()

    // 点击 Chat 来源按钮，验证跳转至目标 Chat 并定位到 thread
    const chatSourceBtn = screen.getByTitle('Chat: chat-abc')
    fireEvent.click(chatSourceBtn)
    expect(mockedNavigate).toHaveBeenCalledWith('/chats/chat-abc?thread=th-1')

    // 测试意图：验证点击 ISSUE_AGENT 来源时通过 query issue 解析 projectId 并精准跳转至 /projects/{id}?issue={id}&thread={threadId}
    vi.spyOn(projectsApi, 'getIssue').mockResolvedValue({
      issue: {
        id: 'issue-101',
        projectId: 'proj-xyz',
        title: 'Fix issue',
        description: '',
        state: 'IN_PROGRESS',
        version: '1',
      },
    } as unknown as IssueDetailDTO)

    const issueSourceBtn = screen.getByTitle('Issue: issue-101 (architect)')
    fireEvent.click(issueSourceBtn)
    await waitFor(() => {
      expect(mockedNavigate).toHaveBeenCalledWith('/projects/proj-xyz?issue=issue-101&thread=th-2')
    })
  })
})
