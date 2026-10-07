import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import type { CanvasDocumentDTO, CanvasSnapshotDTO } from '@/shared/api/contracts/studio'
import {
  createCanvas,
  getCanvas,
  listCanvases,
} from '@/shared/api/studio-service'
import { setLocale } from '@/shared/i18n'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

vi.mock('@/shared/api/studio-service', () => ({
  listCanvases: vi.fn(),
  createCanvas: vi.fn(),
  getCanvas: vi.fn(),
  postCanvasCommands: vi.fn(),
  listCanvasFunctionModels: vi.fn(),
  getCanvasResourceOriginalUrl: vi.fn(),
  getCanvasResourcePreviewUrl: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
  cancelCanvasFunctionRun: vi.fn(),
}))

/** 真实画布文档：wire 上只有 revision；不携带 version/threadId 等旧字段。 */
const documentFixture: CanvasDocumentDTO = {
  id: CANVAS_ID,
  title: 'Research board',
  revision: '3',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
}

const snapshotFixture: CanvasSnapshotDTO = {
  document: documentFixture,
  nodes: [],
  groups: [],
  references: [],
}

beforeEach(() => {
  vi.clearAllMocks()
  setLocale('zh-CN')
  vi.mocked(listCanvases).mockResolvedValue([documentFixture])
  vi.mocked(createCanvas).mockResolvedValue(documentFixture)
  vi.mocked(getCanvas).mockResolvedValue(snapshotFixture)
})

describe('CanvasPage integration', () => {
  it('loads the library from the decoded CanvasDocument without a Thread binding field', async () => {
    renderPage(['/canvas'])

    expect(await screen.findByText('Research board')).toBeInTheDocument()
    expect(listCanvases).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('threadId')).not.toBeInTheDocument()
  })

  it('opens the create dialog and cancels without any write', async () => {
    const user = userEvent.setup()
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '创建新画布' }))
    const dialog = await screen.findByRole('dialog', { name: '创建新画布' })
    const nameInput = within(dialog).getByRole('textbox', { name: '画布名称' })
    expect(nameInput).toHaveValue('')

    await user.type(nameInput, '草稿画布')
    await user.click(within(dialog).getByRole('button', { name: '取消' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(createCanvas).not.toHaveBeenCalled()
    expect(getCanvas).not.toHaveBeenCalled()
  })

  it('creates exactly once after confirming the name and enters the new canvas', async () => {
    const user = userEvent.setup()
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '创建新画布' }))
    const dialog = await screen.findByRole('dialog', { name: '创建新画布' })
    await user.type(within(dialog).getByRole('textbox', { name: '画布名称' }), '研究看板')
    await user.click(within(dialog).getByRole('button', { name: '创建并进入' }))

    await waitFor(() => expect(createCanvas).toHaveBeenCalledTimes(1))
    expect(createCanvas).toHaveBeenCalledWith('研究看板')
    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('blocks an empty name and keeps a repeated submit single-flight while pending', async () => {
    const user = userEvent.setup()
    let resolveCreate: (created: CanvasDocumentDTO) => void = () => undefined
    vi.mocked(createCanvas).mockImplementation(
      () => new Promise<CanvasDocumentDTO>((resolve) => {
        resolveCreate = resolve
      }),
    )
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '创建新画布' }))
    const dialog = await screen.findByRole('dialog', { name: '创建新画布' })
    const confirm = within(dialog).getByRole('button', { name: '创建并进入' })

    // 纯空白名称不发起请求，也不进入 pending（空值由 required 原生校验拦截）。
    await user.type(within(dialog).getByRole('textbox', { name: '画布名称' }), '   ')
    await user.click(confirm)
    expect(createCanvas).not.toHaveBeenCalled()
    expect(within(dialog).getByRole('alert')).toHaveTextContent('请输入画布名称')

    await user.clear(within(dialog).getByRole('textbox', { name: '画布名称' }))
    await user.type(within(dialog).getByRole('textbox', { name: '画布名称' }), '并发画布')
    await user.click(confirm)
    expect(createCanvas).toHaveBeenCalledTimes(1)

    // 在途期间按钮禁用，再次点击不会产生第二个 POST；取消也不会关闭弹窗。
    await waitFor(() => expect(confirm).toBeDisabled())
    await user.click(confirm)
    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(createCanvas).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('dialog', { name: '创建新画布' })).toBeInTheDocument()

    resolveCreate({ ...documentFixture, title: '并发画布' })
    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
  })

  it('keeps the entered name and retries after a failed creation', async () => {
    const user = userEvent.setup()
    vi.mocked(createCanvas).mockRejectedValueOnce(new Error('画布名称已存在'))
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '创建新画布' }))
    const dialog = await screen.findByRole('dialog', { name: '创建新画布' })
    const nameInput = within(dialog).getByRole('textbox', { name: '画布名称' })
    await user.type(nameInput, '重名画布')
    await user.click(within(dialog).getByRole('button', { name: '创建并进入' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('画布名称已存在')
    expect(nameInput).toHaveValue('重名画布')
    expect(createCanvas).toHaveBeenCalledTimes(1)

    await user.click(within(dialog).getByRole('button', { name: '创建并进入' }))
    await waitFor(() => expect(createCanvas).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
  })

  it('opens an existing card at its own id without creating anything', async () => {
    const user = userEvent.setup()
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '进入画布「Research board」' }))

    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
    expect(createCanvas).not.toHaveBeenCalled()
  })

  it('retries the library read after a load error', async () => {
    const user = userEvent.setup()
    vi.mocked(listCanvases).mockRejectedValueOnce(new Error('网络不可用'))
    renderPage(['/canvas'])

    expect(await screen.findByRole('alert')).toHaveTextContent('画布列表加载失败：网络不可用')
    await user.click(screen.getByRole('button', { name: '重试' }))

    expect(await screen.findByText('Research board')).toBeInTheDocument()
    expect(listCanvases).toHaveBeenCalledTimes(2)
  })

  it('redirects non-canonical deep links back to the library', async () => {
    renderPage(['/canvas/invalid-id'])

    expect(await screen.findByText('Research board')).toBeInTheDocument()
    expect(getCanvas).not.toHaveBeenCalled()
  })

  it('loads a canonical deep link with a graph snapshot that has no durable Agent target', async () => {
    renderPage([`/canvas/${CANVAS_ID}`])

    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
    expect(screen.queryByText('threadId')).not.toBeInTheDocument()
  })
})

function renderPage(initialEntries: string[]) {
  const sockets = new FakeWebSocketHarness()
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <Routes>
          <Route path="/canvas" element={<CanvasPage />} />
          <Route path="/canvas/:canvasId" element={<CanvasPage />} />
        </Routes>
      </ApplicationEventProvider>
    </MemoryRouter>,
  )
}
