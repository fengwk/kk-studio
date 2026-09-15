import { describe, expect, it, vi } from 'vitest'
import { createRealtimeFrameScheduler } from '@/features/ai/runtime/realtime-frame-scheduler'

describe('createRealtimeFrameScheduler', () => {
  it('coalesces multiple model dirty notifications into a single frame flush', () => {
    let frameCb: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCb = cb
      return 1
    })
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf })

    scheduler.notifyModelDirty()
    scheduler.notifyModelDirty()
    scheduler.notifyModelDirty()

    expect(raf).toHaveBeenCalledTimes(1)
    expect(onFlush).not.toHaveBeenCalled()

    // 触发帧回调
    frameCb!(performance.now())

    expect(onFlush).toHaveBeenCalledTimes(1)
    expect(onFlush).toHaveBeenCalledWith({ modelDirty: true, toolDirty: false })
  })

  it('combines model and tool dirty notifications in the same frame', () => {
    let frameCb: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCb = cb
      return 2
    })
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf })

    scheduler.notifyModelDirty()
    scheduler.notifyToolDirty()
    scheduler.notifyModelDirty()

    expect(raf).toHaveBeenCalledTimes(1)

    frameCb!(performance.now())

    expect(onFlush).toHaveBeenCalledTimes(1)
    expect(onFlush).toHaveBeenCalledWith({ modelDirty: true, toolDirty: true })
  })

  it('allows cancelModel to clear model dirty without cancelling pending tool dirty', () => {
    let frameCb: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCb = cb
      return 10
    })
    const caf = vi.fn()
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf, caf })

    scheduler.notifyModelDirty()
    scheduler.notifyToolDirty()

    // Model 终态到达：只取消 model dirty，tool 仍在等待帧刷新
    scheduler.cancelModel()
    expect(caf).not.toHaveBeenCalled()

    frameCb!(performance.now())

    expect(onFlush).toHaveBeenCalledWith({ modelDirty: false, toolDirty: true })
  })

  it('allows cancelTool to clear tool dirty without cancelling pending model dirty', () => {
    let frameCb: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCb = cb
      return 20
    })
    const caf = vi.fn()
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf, caf })

    scheduler.notifyModelDirty()
    scheduler.notifyToolDirty()

    // Tool 终态到达：只取消 tool dirty，model 仍在等待帧刷新
    scheduler.cancelTool()
    expect(caf).not.toHaveBeenCalled()

    frameCb!(performance.now())

    expect(onFlush).toHaveBeenCalledWith({ modelDirty: true, toolDirty: false })
  })

  it('cancels scheduled frame when cancelModel is called and tool is not dirty', () => {
    const raf = vi.fn((_cb: FrameRequestCallback) => 30)
    const caf = vi.fn()
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf, caf })

    scheduler.notifyModelDirty()
    scheduler.cancelModel()

    expect(caf).toHaveBeenCalledWith(30)
  })

  it('cancels scheduled frame when cancelTool is called and model is not dirty', () => {
    const raf = vi.fn((_cb: FrameRequestCallback) => 40)
    const caf = vi.fn()
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf, caf })

    scheduler.notifyToolDirty()
    scheduler.cancelTool()

    expect(caf).toHaveBeenCalledWith(40)
  })

  it('cancels scheduled frame and prevents flush from executing on stale frame', () => {
    let frameCb: FrameRequestCallback | null = null
    const raf = vi.fn((cb: FrameRequestCallback) => {
      frameCb = cb
      return 50
    })
    const caf = vi.fn()
    const onFlush = vi.fn()

    const scheduler = createRealtimeFrameScheduler({ onFlush, raf, caf })

    scheduler.notifyModelDirty()
    scheduler.cancel()
    expect(caf).toHaveBeenCalledWith(50)

    // 即使底层回调被异常触发，generation fence 也会丢弃
    if (frameCb) {
      frameCb(performance.now())
    }
    expect(onFlush).not.toHaveBeenCalled()
  })

  it('uses default window rAF and cAF when custom functions are omitted', () => {
    const onFlush = vi.fn()
    const rafSpy = vi.spyOn(window, 'requestAnimationFrame')
    const cafSpy = vi.spyOn(window, 'cancelAnimationFrame')

    const scheduler = createRealtimeFrameScheduler({ onFlush })

    scheduler.notifyModelDirty()
    expect(rafSpy).toHaveBeenCalled()

    scheduler.cancel()
    expect(cafSpy).toHaveBeenCalled()

    rafSpy.mockRestore()
    cafSpy.mockRestore()
  })
})
