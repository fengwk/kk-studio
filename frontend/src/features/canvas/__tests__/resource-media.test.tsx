import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Resource } from '@/features/canvas/domain'
import {
  CanvasResourceMedia,
  CanvasResourceThumbnail,
} from '@/features/canvas/nodes/CanvasResourceMedia'
import {
  getCanvasResourceOriginalUrl,
  getCanvasResourcePreviewUrl,
} from '@/shared/api/studio-service'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(),
  getCanvasResourcePreviewUrl: vi.fn(),
}))

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

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

function resource(kind: Resource['kind'], overrides: Partial<Resource> = {}): Resource {
  return {
    id: '20',
    canvasId: CANVAS_ID,
    ownerNodeId: '2',
    resourceIndex: 0,
    blobId: kind === 'TEXT' ? null : 'blob-asset',
    name: `${kind.toLowerCase()}.asset`,
    textContent: kind === 'TEXT' ? '# Markdown title\n\n- safe list' : null,
    kind,
    mediaType: kind === 'IMAGE'
      ? 'image/png'
      : kind === 'VIDEO'
        ? 'video/mp4'
        : kind === 'AUDIO'
          ? 'audio/mpeg'
          : 'text/markdown',
    sizeBytes: 3,
    width: kind === 'IMAGE'
      ? 1122
      : kind === 'VIDEO'
        ? 1920
        : null,
    height: kind === 'IMAGE'
      ? 1402
      : kind === 'VIDEO'
        ? 1080
        : null,
    durationMs: kind === 'VIDEO' || kind === 'AUDIO' ? 65_000 : null,
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
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

function previewUrl(url: string) {
  return {
    method: 'GET' as const,
    url,
    headers: {},
    expiresAt: '2026-08-10T00:15:00Z',
  }
}

function originalUrl(url: string) {
  return {
    method: 'GET' as const,
    url,
    headers: {},
    expiresAt: '2026-08-10T00:15:00Z',
  }
}

/** 把 lazy preview 的 IntersectionObserver 触发出来。 */
function revealPreview() {
  intersectionCallback([{
    isIntersecting: true,
    target: observe.mock.calls[0]?.[0] as Element,
  } as IntersectionObserverEntry], {} as IntersectionObserver)
}

describe('Canvas lazy resource media', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', IntersectionObserverFake)
    // jsdom 未实现媒体播放；静默 stub 原型，单测再按场景覆盖。
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(() => undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    vi.mocked(getCanvasResourcePreviewUrl).mockResolvedValue(previewUrl('https://s3.example/preview.webp'))
    vi.mocked(getCanvasResourceOriginalUrl).mockResolvedValue(originalUrl('https://s3.example/original'))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    observe.mockClear()
    disconnect.mockClear()
  })

  it('lazily previews IMAGE with aspect metadata and without signing the original', async () => {
    // Preview visibility and original access remain independent signed-URL boundaries.
    renderMedia(resource('IMAGE'))
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
    revealPreview()
    const image = await screen.findByRole('img', { name: 'image.asset' })
    expect(image).toHaveAttribute('src', 'https://s3.example/preview.webp')
    expect(image).toHaveAttribute('width', '1122')
    expect(image).toHaveAttribute('height', '1402')
    expect(image).toHaveAttribute('draggable', 'false')
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
    // 常驻原件操作条已移除。
    expect(document.querySelector('.media-original-actions')).toBeNull()
  })

  it('loads a lazy preview immediately when layout already places it near the viewport', async () => {
    vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([
      {} as DOMRect,
    ] as unknown as DOMRectList)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      bottom: 300,
      height: 200,
      left: 100,
      right: 400,
      top: 100,
      width: 300,
      x: 100,
      y: 100,
      toJSON: () => ({}),
    })

    renderMedia(resource('IMAGE'))

    expect(await screen.findByRole('img', { name: 'image.asset' })).toHaveAttribute(
      'src',
      'https://s3.example/preview.webp',
    )
    expect(observe).not.toHaveBeenCalled()
  })

  it('recovers a stale preview by force-refetching a new signed URL', async () => {
    // 即使 React Query 数据是新鲜的，onError 也会先强制重新签名一次。
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/stale.webp'))
      .mockResolvedValueOnce(previewUrl('https://s3.example/fresh.webp'))
    renderMedia(resource('IMAGE'))
    revealPreview()
    const image = await screen.findByRole('img', { name: 'image.asset' })
    expect(image).toHaveAttribute('src', 'https://s3.example/stale.webp')

    fireEvent.error(image)

    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2))
    await screen.findByRole('img', { name: 'image.asset' }, {})
    await waitFor(() => {
      expect(screen.getByRole('img', { name: 'image.asset' })).toHaveAttribute(
        'src',
        'https://s3.example/fresh.webp',
      )
    })
    // 预览恢复阶段不触碰原件签名边界。
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
  })

  it('falls back to the original when the first preview signature fails before an image exists', async () => {
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockRejectedValueOnce(new Error('preview signing outage'))
      .mockRejectedValueOnce(new Error('preview signing outage'))
    renderMedia(resource('IMAGE'))
    revealPreview()

    await waitFor(() => expect(getCanvasResourceOriginalUrl).toHaveBeenCalledOnce(), { timeout: 4_000 })
    expect(await screen.findByRole('img', { name: 'image.asset' })).toHaveAttribute(
      'src',
      'https://s3.example/original',
    )
  })

  it('falls back to the original when the preview refresh fails', async () => {
    // 刷新失败（含 useQuery 一次自动重试）后请求并渲染原件 URL。
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/stale.webp'))
      .mockRejectedValueOnce(new Error('signing outage'))
      .mockRejectedValueOnce(new Error('signing outage'))
    renderMedia(resource('IMAGE'))
    revealPreview()
    const image = await screen.findByRole('img', { name: 'image.asset' })
    fireEvent.error(image)

    await waitFor(() => expect(getCanvasResourceOriginalUrl).toHaveBeenCalledOnce(), { timeout: 4_000 })
    await waitFor(() => {
      expect(screen.getByRole('img', { name: 'image.asset' })).toHaveAttribute(
        'src',
        'https://s3.example/original',
      )
    })
  })

  it('falls back to the original when the refreshed preview URL also errors', async () => {
    // 同一失败周期：重新签名后的新 URL 再次 error 时停止 preview 重试并回退原件。
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/stale.webp'))
      .mockResolvedValueOnce(previewUrl('https://s3.example/still-broken.webp'))
    renderMedia(resource('IMAGE'))
    revealPreview()
    let image = await screen.findByRole('img', { name: 'image.asset' })
    fireEvent.error(image)

    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2))
    image = await screen.findByRole('img', { name: 'image.asset' })
    expect(image).toHaveAttribute('src', 'https://s3.example/still-broken.webp')
    fireEvent.error(image)

    await waitFor(() => expect(getCanvasResourceOriginalUrl).toHaveBeenCalledOnce())
    await waitFor(() => {
      expect(screen.getByRole('img', { name: 'image.asset' })).toHaveAttribute(
        'src',
        'https://s3.example/original',
      )
    })
    // 回退原件后不再无限重试 preview。
    expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2)
  })

  it('shows an unavailable placeholder when the IMAGE original also fails', async () => {
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/stale.webp'))
      .mockResolvedValueOnce(previewUrl('https://s3.example/refreshed.webp'))
    const view = renderMedia(resource('IMAGE'))
    revealPreview()
    const preview = await screen.findByRole('img', { name: 'image.asset' })
    fireEvent.error(preview)

    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2))
    const refreshed = await screen.findByRole('img', { name: 'image.asset' })
    expect(refreshed).toHaveAttribute('src', 'https://s3.example/refreshed.webp')
    fireEvent.error(refreshed)

    await waitFor(() => {
      const element = view.container.querySelector('img[src="https://s3.example/original"]')
      expect(element).not.toBeNull()
    })
    fireEvent.error(view.container.querySelector('img[src="https://s3.example/original"]') as HTMLImageElement)

    expect(await screen.findByRole('alert')).toHaveTextContent('资源不可用')
    expect(screen.queryByRole('img', { name: 'image.asset' })).not.toBeInTheDocument()
  })

  it('keeps VIDEO on preview until play and recovers the poster', async () => {
    // 显式播放才请求原件；poster 走与 IMAGE 相同的恢复状态机。
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/video-poster.webp'))
      .mockResolvedValueOnce(previewUrl('https://s3.example/refreshed-poster.webp'))
    const view = renderMedia(resource('VIDEO'))
    revealPreview()
    const posterImg = () => view.container.querySelector('.canvas-resource-media.video img') as HTMLImageElement | null
    await waitFor(() => {
      expect(posterImg()?.src).toContain('video-poster.webp')
    })
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()

    fireEvent.error(posterImg() as HTMLImageElement)
    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2))
    await waitFor(() => {
      expect(posterImg()?.src).toContain('refreshed-poster.webp')
    })

    fireEvent.error(posterImg() as HTMLImageElement)
    await waitFor(() => expect(getCanvasResourceOriginalUrl).toHaveBeenCalledOnce())
    await waitFor(() => {
      expect(view.container.querySelector('.canvas-resource-media.video img')).toBeNull()
    })

    const video = await screen.findByLabelText('播放视频 video.asset')
    expect(video).toHaveAttribute('src', 'https://s3.example/original')
    expect(video).toHaveAttribute('controls')
    expect(video).toHaveAttribute('preload', 'metadata')
    expect(video).toHaveAttribute('playsinline')
    expect((video as HTMLVideoElement).autoplay).toBe(false)
    expect(video).toHaveClass('nodrag', 'nowheel')
    view.unmount()
    expect(video).not.toHaveAttribute('src')
  })

  it('shows an unavailable placeholder when the VIDEO original also fails', async () => {
    vi.mocked(getCanvasResourcePreviewUrl)
      .mockResolvedValueOnce(previewUrl('https://s3.example/stale-poster.webp'))
      .mockResolvedValueOnce(previewUrl('https://s3.example/refreshed-poster.webp'))
    const view = renderMedia(resource('VIDEO'))
    revealPreview()
    const poster = () => view.container.querySelector('.canvas-resource-media.video img') as HTMLImageElement | null
    await waitFor(() => expect(poster()?.src).toContain('stale-poster.webp'))
    fireEvent.error(poster() as HTMLImageElement)

    await waitFor(() => expect(getCanvasResourcePreviewUrl).toHaveBeenCalledTimes(2))
    expect(poster()?.src).toContain('refreshed-poster.webp')
    fireEvent.error(poster() as HTMLImageElement)

    const video = await waitFor(() => {
      const element = view.container.querySelector('video')
      expect(element).not.toBeNull()
      expect(element).toHaveAttribute('src', 'https://s3.example/original')
      return element as HTMLVideoElement
    })
    fireEvent.error(video)

    expect(await screen.findByRole('alert')).toHaveTextContent('资源不可用')
    expect(screen.queryByLabelText('播放视频 video.asset')).not.toBeInTheDocument()
  })

  it('loads AUDIO original on first play into the custom player', async () => {
    const view = renderMedia(resource('AUDIO'))
    // 紧凑播放器常驻显示资源名与时间，未播放时不签名原件。
    expect(screen.getByText('audio.asset')).toBeInTheDocument()
    expect(screen.getByText('0:00 / 1:05')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '播放音频 audio.asset' })).toBeInTheDocument()
    // 原件操作只走右键菜单：播放器内只有播放与静音两个按钮，无常驻下载入口。
    expect(screen.getAllByRole('button')).toHaveLength(2)
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
    expect(document.querySelector('audio')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '播放音频 audio.asset' }))
    await waitFor(() => expect(getCanvasResourceOriginalUrl).toHaveBeenCalledOnce())
    const audio = document.querySelector('audio') as HTMLAudioElement
    await waitFor(() => expect(audio).not.toBeNull())
    expect(audio).toHaveAttribute('src', 'https://s3.example/original')
    expect(audio).toHaveClass('nodrag', 'nowheel')
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
    view.unmount()
  })

  it('tracks audio progress/time and seeks through the range input', async () => {
    const user = userEvent.setup()
    renderMedia(resource('AUDIO'))
    fireEvent.click(screen.getByRole('button', { name: '播放音频 audio.asset' }))
    // 等自动播放状态落定后再取 audio 元素，避免 query 解析与 effect 提交竞争。
    await waitFor(() => expect(screen.getByRole('button', { name: '暂停音频 audio.asset' })).toBeInTheDocument())
    const audio = document.querySelector('audio') as HTMLAudioElement

    Object.defineProperty(audio, 'duration', { configurable: true, value: 65 })
    let current = 0
    Object.defineProperty(audio, 'currentTime', {
      configurable: true,
      get: () => current,
      set: (value: number) => {
        current = value
      },
    })
    fireEvent(audio, new Event('loadedmetadata'))
    expect(screen.getByText('0:00 / 1:05')).toBeInTheDocument()

    current = 30
    fireEvent(audio, new Event('timeupdate'))
    expect(screen.getByText('0:30 / 1:05')).toBeInTheDocument()

    const seek = screen.getByRole('slider', { name: '调节 audio.asset 的播放位置' })
    await user.click(seek)
    fireEvent.change(seek, { target: { value: '45' } })
    expect(screen.getByText('0:45 / 1:05')).toBeInTheDocument()
    expect(audio.currentTime).toBe(45)
  })

  it('controls audio volume and mute and surfaces a rejected play promise', async () => {
    const user = userEvent.setup()
    renderMedia(resource('AUDIO'))
    fireEvent.click(screen.getByRole('button', { name: '播放音频 audio.asset' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '暂停音频 audio.asset' })).toBeInTheDocument())
    const audio = document.querySelector('audio') as HTMLAudioElement

    const volume = screen.getByRole('slider', { name: 'audio.asset 的音量' })
    fireEvent.change(volume, { target: { value: '0.4' } })
    expect(audio.volume).toBe(0.4)
    expect(screen.getByRole('button', { name: '静音 audio.asset' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )

    await user.click(screen.getByRole('button', { name: '静音 audio.asset' }))
    expect(audio.muted).toBe(true)
    expect(screen.getByRole('button', { name: '取消静音 audio.asset' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // play() 拒绝时回到暂停态并显示可访问错误。
    vi.spyOn(audio, 'play').mockRejectedValueOnce(new Error('autoplay blocked'))
    fireEvent.click(screen.getByRole('button', { name: '暂停音频 audio.asset' }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('无法开始播放')
    })
    expect(screen.getByRole('button', { name: '播放音频 audio.asset' })).toBeInTheDocument()
  })

  it('reuses the safe MarkdownRenderer for TEXT resources', () => {
    renderMedia(resource('TEXT'))
    expect(screen.getByRole('heading', { name: 'Markdown title' })).toBeInTheDocument()
    expect(screen.getByRole('list')).toHaveTextContent('safe list')
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
    expect(getCanvasResourceOriginalUrl).not.toHaveBeenCalled()
  })

  it('uses local AUDIO/TEXT thumbnail icons without signing preview URLs', () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const view = render(
      <QueryClientProvider client={client}>
        <CanvasResourceThumbnail resource={resource('AUDIO')} />
      </QueryClientProvider>,
    )
    expect(screen.getByText('♪')).toBeInTheDocument()

    view.rerender(
      <QueryClientProvider client={client}>
        <CanvasResourceThumbnail resource={resource('TEXT')} />
      </QueryClientProvider>,
    )
    expect(screen.getByText('T')).toBeInTheDocument()
    expect(getCanvasResourcePreviewUrl).not.toHaveBeenCalled()
  })
})
