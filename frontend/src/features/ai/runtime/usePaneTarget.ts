import { useCallback, useEffect, useRef, useState } from 'react'
import {
  loadPaneTarget,
  savePaneTarget,
} from '@/features/ai/runtime/agent-pane'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'

/**
 * Pane 绑定目标：父面板唯一持有的目标状态与持久化。
 *
 * 只有目标与持久化在这里；控制面（草稿、上传、人工执行与命令）由根控组件按目标
 * 身份挂载，子代理目标与身份未确认时不会挂载它们。
 */
export function usePaneTarget({
  owner,
  paneId,
  initialTarget,
}: {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  initialTarget?: PaneTarget
}) {
  const [target, setTargetState] = useState<PaneTarget>(
    () => initialTarget ?? (owner
      ? (owner.type === 'CHAT' ? loadPaneTarget(owner, paneId) : { kind: 'NEW_SESSION_DRAFT' })
      : { kind: 'BOUND_THREAD', threadId: paneId }),
  )
  const targetRef = useRef(target)
  useEffect(() => {
    targetRef.current = target
    if (owner?.type === 'CHAT') {
      savePaneTarget(owner, paneId, target)
    }
  }, [owner, paneId, target])

  // 同步更新镜像：控制面在 state commit 之前读到的必须已是新绑定。
  const setTarget = useCallback((next: PaneTarget) => {
    targetRef.current = next
    setTargetState(next)
  }, [])

  return { target, targetRef, setTarget }
}
