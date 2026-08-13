import type { CSSProperties } from 'react'
import type { ResourceNode } from '@/features/canvas/domain'
import { referenceAlias } from '@/features/canvas/generation'
import { CanvasResourceRenderer } from '@/features/canvas/nodes/resources/CanvasResourceRenderer'
import { resourceGridLayout } from '@/features/canvas/resource-grid-layout'
import { useI18n } from '@/shared/i18n'

export function CanvasResourceGrid({ node }: { node: ResourceNode }) {
  const { t } = useI18n()
  const layout = resourceGridLayout(Math.max(1, node.resources.length))
  const style = {
    '--canvas-resource-grid-cols': layout.cols,
    '--canvas-resource-grid-rows': layout.rows,
  } as CSSProperties

  return (
    <div
      className={`canvas-resource-grid ${
        node.resources.length <= 1 ? 'single' : 'multiple'
      }`}
      data-count={node.resources.length}
      data-cols={layout.cols}
      data-rows={layout.rows}
      style={style}
    >
      {node.resources.length === 0 ? (
        <div className="canvas-resource-slot empty">
          <div className="resource-empty">
            <span aria-hidden="true">✦</span>
            <p>{node.function ? t('canvas.node.emptyFunction') : t('canvas.node.empty')}</p>
          </div>
        </div>
      ) : node.resources.map((resource, index) => {
        const alias = referenceAlias(node, index)
        return (
          <div
            key={resource.id}
            className="canvas-resource-slot"
            data-resource-kind={resource.kind}
            aria-label={alias}
            title={alias}
          >
            <span className="canvas-resource-index" aria-hidden="true">{index}</span>
            <CanvasResourceRenderer resource={resource} />
          </div>
        )
      })}
    </div>
  )
}
