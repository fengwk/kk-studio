import { useCallback, useLayoutEffect, useMemo, useRef, type KeyboardEvent } from 'react'
import type { ThreadProjection } from '../useThreadProjection'
import { formatModelLabel } from '@/features/catalog'
import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'

/**
 * Debug 视图的退出键：内层已消费的 Esc（如先清选中事件）、重复按键、输入法组合与阻塞弹窗内的 Esc 都不退出。
 */
export function isDebugCloseEscape(event: KeyboardEvent<HTMLElement>): boolean {
  return event.key === 'Escape' && !event.defaultPrevented && !event.repeat
    && !event.nativeEvent.isComposing && event.keyCode !== 229
    && !shouldDeferToBlockingModal(event.currentTarget)
}

/** 子 Thread 查看头部使用的身份与父级返回动作。 */
export interface ThreadPresentation {
  viewKey: string
  threadId: string | null
  name: string | null
  parentThreadId: string | null
  identity: string | null
  act: (viewKey: string, action: 'parent') => void
}

/**
 * 线程头身份：Agent 与实际生效的 model + variant。variant 直接来自 branch settings
 * 快照，不依赖 catalog 是否加载到该模型，也不把 variant 换算成 reasoning effort。
 */
export function threadIdentity(projection: Pick<ThreadProjection, 'thread'>): string | null {
  const settings = projection.thread?.branchSettings
  if (!settings) {
    return null
  }
  const model = getBuiltinModelSelection(settings.execution)
  return [
    settings.agentName,
    model ? formatModelLabel(model.modelName, model.variant) : null,
  ].filter(Boolean).join(' · ')
}

/** 父级返回动作只允许由当前可见且激活的子 Thread 查看层触发。 */
export function useThreadPresentation({
  projection, threadId, active, onParent,
}: {
  projection: Pick<ThreadProjection, 'thread'>
  threadId: string
  active: boolean
  onParent: (parentThreadId: string) => void
}) {
  const latest = useRef({ projection, threadId, active, onParent })
  const mounted = useRef(false)
  useLayoutEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useLayoutEffect(() => {
    latest.current = { projection, threadId, active, onParent }
  })
  const act = useCallback((viewKey: string, action: 'parent') => {
    const current = latest.current
    if (!mounted.current || !current.active || current.threadId !== viewKey) {
      return
    }
    const thread = current.projection.thread?.threadId === current.threadId
      ? current.projection.thread : null
    if (thread?.parentThreadId && action === 'parent') {
      current.onParent(thread.parentThreadId)
    }
  }, [])
  const thread = projection.thread?.threadId === threadId ? projection.thread : null
  const identity = thread ? threadIdentity(projection) : null
  return useMemo<ThreadPresentation>(() => ({
    viewKey: threadId,
    threadId: thread?.threadId ?? null,
    name: thread?.parentThreadId && thread.name === 'main' ? null : thread?.name ?? null,
    parentThreadId: thread?.parentThreadId ?? null,
    identity,
    act,
  }), [threadId, thread?.threadId, thread?.name, thread?.parentThreadId, identity, act])
}
