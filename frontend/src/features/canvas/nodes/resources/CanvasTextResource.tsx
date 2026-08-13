import type { Resource } from '@/features/canvas/domain'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

export function CanvasTextResource({ resource }: { resource: Resource }) {
  return (
    <div className="canvas-markdown-resource" data-testid="canvas-markdown-resource">
      <MarkdownRenderer content={resource.textContent ?? ''} />
    </div>
  )
}
