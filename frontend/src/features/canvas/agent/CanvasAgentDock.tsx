import { useId, type RefObject } from 'react'
import { CanvasAddMenu } from '@/features/canvas/agent/CanvasAddMenu'
import { CanvasAgentComposer } from '@/features/canvas/agent/CanvasAgentComposer'
import { CanvasAgentThread } from '@/features/canvas/agent/CanvasAgentThread'

/**
 * Canvas Agent panel shell.
 * Composition only — thread, add menu, and composer stay modular like
 * ChatPanel + ChatTranscript + ChatComposer, and pi's per-message components.
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
