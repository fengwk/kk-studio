import { CanvasEditor } from './CanvasEditor'
import { CanvasOverlays } from './CanvasOverlays'
import { CanvasRuntimeProvider } from './CanvasRuntimeContext'
import './canvas.css'

export function CanvasWorkbench({ canvasId }: { canvasId: string }) {
  return <CanvasRuntimeProvider key={canvasId} initialCanvasId={canvasId}>
    <div className="canvas-feature canvas-editor-active">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
  </CanvasRuntimeProvider>
}
