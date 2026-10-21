/**
 * 实时帧合并调度器选项。
 */
export interface RealtimeFrameSchedulerOptions {
  /** 帧回调触发时的合并刷新回调 */
  onFlush?: (flags: { modelDirty: boolean; toolDirty: boolean }) => void
  /** 可定制的 requestAnimationFrame 实现（便于测试精确控制帧生命周期） */
  raf?: (callback: FrameRequestCallback) => number
  /** 可定制的 cancelAnimationFrame 实现 */
  caf?: (handle: number) => void
}

/**
 * 实时帧合并调度器：
 * 在单次 requestAnimationFrame 内合并消费累积的 model/tool overlay 变更，
 * 避免频繁的高频 delta/partial 造成大量 React 级级联 re-render。
 *
 * 核心保证：
 * 1. 每一帧发布当前累积的全部内容，绝不进行字符级缓动/分段打字截断。
 * 2. 支持按 model / tool 独立取消脏标记，确保任一到达终态不会抹掉另一方的未决刷新。
 * 3. 线程切换、卸载或全局重置时通过 cancel 清理所有未决帧，保证未来帧绝不会覆盖权威 snapshot。
 */
export interface RealtimeFrameScheduler {
  /** 标记 model overlay 有待发布的累积变更，并确保已请求帧 */
  notifyModelDirty: () => void
  /** 标记 tool overlay 有待发布的累积变更，并确保已请求帧 */
  notifyToolDirty: () => void
  /** 取消 model 的未决刷新；若 tool 也无脏标记则取消底层帧 */
  cancelModel: () => void
  /** 取消 tool 的未决刷新；若 model 也无脏标记则取消底层帧 */
  cancelTool: () => void
  /** 取消并作废当前待触发的帧回调，清理所有脏标记 */
  cancel: () => void
  /** 动态更新帧刷新回调 */
  setOnFlush: (onFlush: (flags: { modelDirty: boolean; toolDirty: boolean }) => void) => void
}

export function createRealtimeFrameScheduler(
  options: RealtimeFrameSchedulerOptions = {},
): RealtimeFrameScheduler {
  let frameHandle: number | null = null
  let generation = 0
  let modelDirty = false
  let toolDirty = false
  let flushHandler = options.onFlush

  const setOnFlush = (handler: (flags: { modelDirty: boolean; toolDirty: boolean }) => void) => {
    flushHandler = handler
  }

  const requestFrame =
    options.raf ??
    ((cb: FrameRequestCallback) => {
      if (typeof requestAnimationFrame === 'function') {
        return requestAnimationFrame(cb)
      }
      return setTimeout(cb, 16) as unknown as number
    })

  const cancelFrame =
    options.caf ??
    ((handle: number) => {
      if (typeof cancelAnimationFrame === 'function') {
        cancelAnimationFrame(handle)
        return
      }
      clearTimeout(handle as unknown as ReturnType<typeof setTimeout>)
    })

  const scheduleIfNeeded = () => {
    if (frameHandle != null) {
      return
    }
    const capturedGen = generation
    frameHandle = requestFrame(() => {
      if (generation !== capturedGen) {
        return
      }
      frameHandle = null
      const wasModelDirty = modelDirty
      const wasToolDirty = toolDirty
      modelDirty = false
      toolDirty = false
      if (wasModelDirty || wasToolDirty) {
        flushHandler?.({ modelDirty: wasModelDirty, toolDirty: wasToolDirty })
      }
    })
  }

  const cancelModel = () => {
    modelDirty = false
    if (!toolDirty && frameHandle != null) {
      cancelFrame(frameHandle)
      frameHandle = null
      generation += 1
    }
  }

  const cancelTool = () => {
    toolDirty = false
    if (!modelDirty && frameHandle != null) {
      cancelFrame(frameHandle)
      frameHandle = null
      generation += 1
    }
  }

  const cancel = () => {
    if (frameHandle != null) {
      cancelFrame(frameHandle)
      frameHandle = null
    }
    generation += 1
    modelDirty = false
    toolDirty = false
  }

  const notifyModelDirty = () => {
    modelDirty = true
    scheduleIfNeeded()
  }

  const notifyToolDirty = () => {
    toolDirty = true
    scheduleIfNeeded()
  }

  return {
    notifyModelDirty,
    notifyToolDirty,
    cancelModel,
    cancelTool,
    cancel,
    setOnFlush,
  }
}
