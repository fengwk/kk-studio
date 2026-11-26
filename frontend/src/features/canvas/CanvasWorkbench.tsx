import { CanvasEditor } from './CanvasEditor'
import { CanvasOverlays } from './CanvasOverlays'
import { CanvasRuntimeProvider } from './CanvasRuntimeContext'
import './canvas.css'

export function CanvasWorkbench({ canvasId }: { canvasId: string }) {
  return <CanvasRuntimeProvider key={canvasId} canvasId={canvasId}>
    <div className="canvas-feature canvas-workbench">
      <CanvasEditor />
      <CanvasOverlays />
    </div>
  </CanvasRuntimeProvider>
}
