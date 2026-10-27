import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import type { PaneTarget } from '@/features/chat/runtime/agent-pane'

/**
 * Pane 绑定目标：有共享 tab 状态时完全受控，没有时保持宿主内局部状态。
 *
 * Chat workspace 由父页唯一持有呈现状态；本 Hook 只镜像最新目标并在用户事件中
 * 请求父页提交。它不读取也不写任何旧的 per-pane 呈现键。
 */
export function usePaneTarget({
  initialTarget,
  target: sharedTarget,
  onTargetChange,
}: {
  initialTarget?: PaneTarget
  target?: PaneTarget
  onTargetChange?: (next: PaneTarget) => void
}) {
  const [localTarget, setLocalTarget] = useState<PaneTarget>(
    () => initialTarget ?? { kind: 'NEW_SESSION_DRAFT' },
  )
  const controlled = sharedTarget !== undefined
  const target = controlled ? sharedTarget : localTarget
  const targetRef = useRef(target)
  // layout effect 在子级 effect 前同步镜像，避免父级提交后旧目标仍被消费一次。
  useLayoutEffect(() => {
    targetRef.current = target
  }, [target])

  const setTarget = useCallback((next: PaneTarget) => {
    // 同步更新镜像：控制面在 state commit 之前读到的必须已是新绑定。
    targetRef.current = next
    if (controlled) {
      onTargetChange?.(next)
      return
    }
    setLocalTarget(next)
  }, [controlled, onTargetChange])

  return { target, targetRef, setTarget }
}
