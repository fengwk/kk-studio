import { render, screen, waitFor } from '@testing-library/react'
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

const documentFixture: CanvasDocumentDTO = {
  id: CANVAS_ID,
  title: 'Research board',
  version: '3',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
}

const snapshotFixture: CanvasSnapshotDTO = {
  document: documentFixture,
  nodes: [],
  groups: [],
  links: [],
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

  it('creates a canvas from the library and navigates to its editor', async () => {
    const user = userEvent.setup()
    renderPage(['/canvas'])

    await user.click(await screen.findByRole('button', { name: '创建新画布' }))
    await waitFor(() => expect(createCanvas).toHaveBeenCalledWith('未命名画布'))
    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
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
