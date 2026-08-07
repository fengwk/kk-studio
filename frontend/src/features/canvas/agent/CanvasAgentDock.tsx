import { useId, type RefObject } from 'react'
import { CanvasAddMenu } from '@/features/canvas/agent/CanvasAddMenu'
import { CanvasAgentComposer } from '@/features/canvas/agent/CanvasAgentComposer'
import { CanvasAgentThread } from '@/features/canvas/agent/CanvasAgentThread'

/**
 * Canvas Agent 面板外壳。
 * 仅做组合 —— thread、add menu、composer 保持模块化，
 * 类似 ChatPanel + ChatTranscript + ChatComposer 以及 pi 的按消息类型组件。
 */
export function CanvasAgentDock({
  dockWrapRef,
}: {
  dockWrapRef: RefObject<HTMLDivElement | null>
}) {
  const menuId = useId()

  return (
    <div className="agent-dock-wrap" id="agentDockWrap" ref={dockWrapRef}>
      <CanvasAgentThread />
      <CanvasAddMenu menuId={menuId} />
      <CanvasAgentComposer menuId={menuId} />
    </div>
  )
}
