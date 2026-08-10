import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Resource } from '@/features/canvas/domain'
import { CanvasResourceMedia } from '@/features/canvas/nodes/CanvasResourceMedia'
import {
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
} from '@/shared/api/studio-service'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(),
  getCanvasResourcePreviewUrl: vi.fn(),
}))

let intersectionCallback: IntersectionObserverCallback
const observe = vi.fn()
const disconnect = vi.fn()

class IntersectionObserverFake {
  constructor(callback: IntersectionObserverCallback) {
    intersectionCallback = callback
  }

  observe = observe
  disconnect = disconnect
  unobserve = vi.fn()
  root = null
  rootMargin = ''
  thresholds = []
  takeRecords = () => []
}

function resource(kind: Resource['kind']): Resource {
  return {
    id: '20',
    canvasId: '1',
    kind,
    mediaType: kind === 'IMAGE'
      ? 'image/png'
      : kind === 'VIDEO'
        ? 'video/mp4'
        : kind === 'AUDIO'
          ? 'audio/mpeg'
          : 'text/markdown',
    name: `${kind.toLowerCase()}.asset`,
    size: '3',
    text: kind === 'TEXT' ? '# Markdown title\n\n- safe list' : null,
    metadata: kind === 'AUDIO' ? { durationMs: 65_000 } : {},
    createdAt: '2026-08-10T00:00:00Z',
  }
}

function renderMedia(value: Resource) {
  return render(
    <QueryClientProvider client={new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })}>
      <CanvasResourceMedia resource={value} />
    </QueryClientProvider>,
  )
}

describe('Canvas lazy resource media', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', IntersectionObserverFake)
    vi.mocked(getCanvasResourcePreviewUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://s3.example/preview.webp',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValue({
      method: 'GET',
      url: 'https://s3.example/original',
      headers: {},
      expiresAt: '2026-08-10T00:15:00Z',
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    observe.mockClear()
    disconnect.mockClear()
  })

  it('lazily previews IMAGE and signs original open/download actions only after a click', async () => {
    // Preview visibility and original access remain independent signed-URL boundaries.
    const view = renderMedia(resource('IMAGE'))
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
    intersectionCallback([{
      isIntersecting: true,
      target: observe.mock.calls[0]?.[0] as Element,
    } as IntersectionObserverEntry], {} as IntersectionObserver)
    expect(await screen.findByRole('img', { name: 'image.asset' })).toHaveAttribute(
      'src',
      'https://s3.example/preview.webp',
    )
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '获取原图 image.asset' }))
    expect(await screen.findByRole('link', { name: '打开原图 image.asset' })).toHaveAttribute(
      'href',
      'https://s3.example/original',
    )
    expect(screen.getByRole('link', { name: '下载原图 image.asset' })).toHaveAttribute(
      'download',
      'image.asset',
    )
    view.unmount()
    expect(disconnect).toHaveBeenCalled()
  })

  it('keeps VIDEO on preview until play and cleans the original media element on unmount', async () => {
    // Explicit play is the boundary that permits an original URL request.
    const view = renderMedia(resource('VIDEO'))
    intersectionCallback([{
      isIntersecting: true,
      target: observe.mock.calls[0]?.[0] as Element,
    } as IntersectionObserverEntry], {} as IntersectionObserver)
    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledOnce())
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '获取视频原件 video.asset' }))
    expect(await screen.findByRole('link', { name: '下载视频原件 video.asset' })).toHaveAttribute(
      'href',
      'https://s3.example/original',
    )
    expect(screen.queryByLabelText('播放 video.asset')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '播放视频 video.asset' }))
    const video = await screen.findByLabelText('播放 video.asset')
    expect(video).toHaveAttribute('src', 'https://s3.example/original')
    view.unmount()
    expect(video).not.toHaveAttribute('src')
  })

  it('loads AUDIO original on demand and renders metadata duration', async () => {
    // Audio has no preview endpoint and exposes duration before original bytes are requested.
    renderMedia(resource('AUDIO'))
    expect(screen.getByText('1:05')).toBeInTheDocument()
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '获取音频原件 audio.asset' }))
    expect(await screen.findByRole('link', { name: '下载音频原件 audio.asset' })).toHaveAttribute(
      'download',
      'audio.asset',
    )
    expect(screen.queryByLabelText('播放音频 audio.asset')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /加载音频/ }))
    expect(await screen.findByLabelText('播放音频 audio.asset')).toHaveAttribute(
      'src',
      'https://s3.example/original',
    )
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
  })

  it('reuses the safe MarkdownRenderer for TEXT resources', () => {
    // Heading/list semantics demonstrate TEXT is rendered rather than injected as raw HTML.
    renderMedia(resource('TEXT'))
    expect(screen.getByRole('heading', { name: 'Markdown title' })).toBeInTheDocument()
    expect(screen.getByRole('list')).toHaveTextContent('safe list')
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
  })
})
