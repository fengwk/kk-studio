import { CanvasEditor } from './CanvasEditor'
import { CanvasOverlays } from './CanvasOverlays'
import { CanvasRuntimeProvider } from './CanvasRuntimeContext'
import { canvasViewKey } from './canvas-view-key'
import './canvas.css'

export function CanvasWorkbench({ canvasId, viewSlot, userId }: { canvasId: string; viewSlot: number; userId: string }) {
  return <CanvasRuntimeProvider key={`${userId}:${canvasViewKey(canvasId, viewSlot)}`} canvasId={canvasId} viewSlot={viewSlot} userId={userId}>
    <div className="canvas-feature canvas-workbench">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
  </CanvasRuntimeProvider>
}
