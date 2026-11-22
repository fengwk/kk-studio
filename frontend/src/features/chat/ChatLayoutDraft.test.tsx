import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ChatWorkspacePage } from '@/features/chat/ChatWorkspacePage'
import { chatService } from '@/features/chat/chat-service'
import { storageService } from '@/shared/api/storage-service'
import { setLocale } from '@/shared/i18n'

vi.mock('@/features/chat/chat-service', () => ({
  chatService: {
    getChat: vi.fn(),
    listChatSessions: vi.fn(async () => ({ items: [], nextCursor: null })),
  },
}))
vi.mock('@/features/environment/environment-service', () => ({
  environmentService: { listEnvironments: vi.fn(async () => []) },
}))
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(async () => [{
      name: 'assistant',
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    }]),
    listModels: vi.fn(async () => [{
      name: 'MiniMax',
      config: { defaultVariant: 'default', variants: [{ id: 'default' }], abilities: { inputModalities: ['TEXT'] } },
    }]),
  },
}))
vi.mock('@/shared/app-events', () => {
  const manager = {
    subscribe: () => () => undefined,
    subscribeIdentity: () => () => undefined,
    getIdentity: () => null,
    requestThreadRefresh: () => true,
    requestResourceRefresh: () => true,
  }
  return {
    useApplicationEvents: () => manager,
    useApplicationEventConnectionState: () => ({ status: 'ready', reason: null }),
  }
})
vi.mock('@/shared/api/storage-service', () => ({
  storageService: {
    reserveUpload: vi.fn(async () => ({
      id: '99999999-8888-4777-8666-555555555555',
      type: 'PENDING',
      blobId: null,
      presignedPut: { method: 'PUT', url: 'https://storage.test/put', headers: {} },
    })),
    uploadFile: vi.fn(async () => undefined),
    completeUpload: vi.fn(async (id: string) => ({ id, state: 'READY', blobId: 'blob-1' })),
    deleteUpload: vi.fn(async () => undefined),
  },
}))

class HashWorker extends EventTarget {
  postMessage({ requestId }: { requestId: string }) {
    queueMicrotask(() => this.dispatchEvent(new MessageEvent('message', {
      data: { requestId, sha256: 'a'.repeat(64) },
    })))
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  setLocale('zh-CN')
  vi.stubGlobal('Worker', HashWorker)
  vi.mocked(chatService.getChat).mockResolvedValue({
    id: 'chat-1', title: 'Workspace', agentName: 'assistant', environmentName: null,
    yoloEnabled: false, version: '1', createdAt: 0, updatedAt: 0,
  })
})
afterEach(() => vi.unstubAllGlobals())

function renderWorkspace() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(
    [{ path: '/chats/:chatId', element: <ChatWorkspacePage /> }],
    { initialEntries: ['/chats/chat-1'] },
  )
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
}

const pane2 = () => document.querySelector<HTMLElement>('[data-pane-id="pane-2"]')!
const editor = () => within(pane2()).getByRole('textbox', { name: '给 AI 发送消息' })
const fileName = 'review.txt'
const chip = () => pane2().querySelector(`.composer-pill[data-filename="${fileName}"]`)

// Layout changes in the same page should preserve pane 2's unsent draft, including a ready attachment.
it.each([
  { scenario: 'text only (control)', attach: false },
  { scenario: 'text and ready attachment', attach: true },
])('preserves the pane 2 draft after layout 2 → 1 → 2: $scenario', async ({ attach }) => {
  const user = userEvent.setup()
  const draft = 'unsent pane two draft'
  renderWorkspace()
  const layout = await screen.findByRole('button', { name: '布局' })
  const chooseLayout = async (panes: number) => {
    await user.click(layout)
    await user.click(screen.getByRole('option', { name: `${panes} 个窗格` }))
  }

  await chooseLayout(2)
  await user.type(editor(), draft)
  if (attach) {
    const fileInput = pane2().querySelector<HTMLInputElement>('input[type="file"]')!
    await user.upload(fileInput, new File(['review attachment'], fileName, { type: 'text/plain' }))
    expect(chip()).toBeVisible()
    await waitFor(() => {
      expect(storageService.completeUpload).toHaveBeenCalledWith('99999999-8888-4777-8666-555555555555')
      expect(within(pane2()).getByRole('button', { name: '发送消息' })).toBeEnabled()
    })
  }
  expect(editor()).toHaveTextContent(draft)

  await chooseLayout(1)
  expect(screen.getAllByRole('textbox', { name: '给 AI 发送消息' })).toHaveLength(1)
  await chooseLayout(2)
  expect(screen.getAllByRole('textbox', { name: '给 AI 发送消息' })).toHaveLength(2)

  expect.soft(editor().textContent, 'Pane 2 still contains the unsent text').toContain(draft)
  if (attach) {
    expect(chip()).toBeVisible()
    expect(within(pane2()).getByRole('button', { name: '发送消息' })).toBeEnabled()
  }
})

// Drive the PUT to a definite failure while slot 8 is hidden; retry keeps the original draft part.
it('keeps an in-flight upload in a hidden slot and allows retry on return', async () => {
  let reject!: (reason: Error) => void
  const promise = new Promise<void>((_resolve, fail) => { reject = fail })
  vi.mocked(storageService.uploadFile).mockReturnValueOnce(promise)
  const user = userEvent.setup()
  renderWorkspace()
  const layout = await screen.findByRole('button', { name: '布局' })
  const chooseLayout = async (panes: number) => {
    await user.click(layout)
    await user.click(screen.getByRole('option', { name: `${panes} 个窗格` }))
  }
  await chooseLayout(8)
  const pane = document.querySelector<HTMLElement>('[data-pane-id="pane-8"]')!
  await user.upload(pane.querySelector<HTMLInputElement>('input[type="file"]')!,
    new File(['review attachment'], fileName, { type: 'text/plain' }))
  await waitFor(() => expect(storageService.uploadFile).toHaveBeenCalledTimes(1))
  await chooseLayout(1)
  expect(pane).toHaveAttribute('inert')
  expect(pane).not.toBeVisible()
  await act(async () => reject(new Error('PUT failed')))
  await chooseLayout(8)
  expect(within(pane).getByText('上传失败')).toBeVisible()
  expect(within(pane).getByRole('button', { name: '发送消息' })).toBeDisabled()
  await user.click(within(pane).getByRole('button', { name: `重试上传 ${fileName}` }))
  await waitFor(() => expect(within(pane).getByRole('button', { name: '发送消息' })).toBeEnabled())
  expect(storageService.uploadFile).toHaveBeenCalledTimes(2)
})
