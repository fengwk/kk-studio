import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { getCanvas } from '@/features/canvas/canvas-api'
import { setLocale } from '@/shared/i18n'
import type { CanvasDocumentDTO, CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

vi.mock('@/features/canvas/canvas-api', () => ({
  getCanvas: vi.fn(),
  postCanvasCommands: vi.fn(),
  listCanvasFunctions: vi.fn(),
  getCanvasResourceOriginalUrl: vi.fn(),
  getCanvasResourcePreviewUrl: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
  cancelCanvasFunctionRun: vi.fn(),
}))

const documentFixture: CanvasDocumentDTO = {
  id: CANVAS_ID,
  title: 'Research board',
  revision: '3',
  createdAt: 1786291200000,
  updatedAt: 1786291200000,
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
  vi.mocked(getCanvas).mockResolvedValue(snapshotFixture)
})

describe('CanvasPage integration', () => {
  it('loads the editor from a canonical Canvas ID without a Thread binding field', async () => {
    renderPage([`/canvas/${CANVAS_ID}`])

    await waitFor(() => expect(getCanvas).toHaveBeenCalledWith(CANVAS_ID, expect.anything()))
    expect(await screen.findByText('Research board')).toBeInTheDocument()
    expect(screen.queryByText('threadId')).not.toBeInTheDocument()
  })

  it('rejects a non-canonical Canvas ID without requesting a snapshot', async () => {
    renderPage(['/canvas/invalid-id'])

    expect(await screen.findByTestId('outside-canvas')).toBeInTheDocument()
    expect(getCanvas).not.toHaveBeenCalled()
  })
})

function renderPage(initialEntries: string[]) {
  const sockets = new FakeWebSocketHarness()
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        <Routes>
          <Route path="/" element={<div data-testid="outside-canvas" />} />
          <Route path="/canvas/:canvasId" element={<CanvasPage />} />
        </Routes>
      </ApplicationEventProvider>
    </MemoryRouter>,
  )
}
