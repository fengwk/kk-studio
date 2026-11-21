import { useLayoutEffect, useRef, useState } from 'react'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import './cards.css'

/** 骨架形状：普通资源卡（头部 + 元信息 + 动作）与环境摘要卡（头部 + 状态行 + 动作）。 */
export type ResourceCardSkeletonVariant = 'resource' | 'environment'

export interface ResourceCardSkeletonProps {
  /** 读屏加载说明，默认“正在加载资源...” */
  label?: string
  /** 骨架形状，默认普通资源卡。 */
  variant?: ResourceCardSkeletonVariant
  /** 栅格修饰类名；调用方改写过 `.resource-grid` 的列模板时必须传入同一个类名。 */
  className?: string
}

interface SkeletonShape {
  /** 标题行右侧状态标记位（环境摘要的可用性徽标）。 */
  badge: boolean
  /** 头部之下的一条核心状态行（环境摘要“需要处理的状态”）。 */
  status: boolean
  /** 元信息行宽度（百分比）：行数与宽度跟随真实卡结构，不写死高度。 */
  metaRows: number[]
  /** 底部动作位宽度（px）。 */
  actions: number[]
}

const SHAPES: Record<ResourceCardSkeletonVariant, SkeletonShape> = {
  resource: { badge: false, status: false, metaRows: [90, 90, 60], actions: [68, 68] },
  environment: { badge: true, status: true, metaRows: [90, 70], actions: [84] },
}

/** 浏览器会将实际布局的 auto-fill 栅格解析为像素轨道；隐藏时保留一个占位。 */
function resolveSkeletonColumns(grid: Element): number {
  const tracks = getComputedStyle(grid).gridTemplateColumns.trim().split(/\s+/)
  return tracks.every((track) => /^\d+(\.\d+)?px$/.test(track)) ? tracks.length : 1
}

/**
 * 资源卡列表骨架屏：仅用于没有缓存的首次读取。
 * 与真实列表共用 ResourceGrid 和卡片基础间距，按栅格实际列数绘制首行；结果数量未知时
 * 允许首行随列数自然变化，不为假装零跳变固定卡片高度。容器只有一处读屏加载状态，
 * 骨架不可聚焦，`prefers-reduced-motion` 下静态。
 */
export function ResourceCardSkeleton({
  label = '正在加载资源...',
  variant = 'resource',
  className,
}: ResourceCardSkeletonProps) {
  const gridRef = useRef<HTMLDivElement>(null)
  const [columns, setColumns] = useState(1)

  useLayoutEffect(() => {
    const grid = gridRef.current
    if (grid == null) {
      return
    }
    const sync = () => setColumns(resolveSkeletonColumns(grid))
    sync()
    if (typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(sync)
    observer.observe(grid)
    return () => observer.disconnect()
  }, [])

  const shape = SHAPES[variant]

  return (
    <div className="resource-skeleton-container" role="status" aria-busy="true">
      <span className="sr-only">{label}</span>
      <ResourceGrid className={className} ref={gridRef}>
        {Array.from({ length: columns }, (_, cardIndex) => (
          <div key={cardIndex} className="resource-card resource-card-skeleton" aria-hidden="true">
            <div className="resource-card-head">
              <div className="skeleton-box skeleton-icon" />
              <div className="resource-card-head-text">
                <div className="skeleton-box skeleton-title" />
                <div className="skeleton-box skeleton-subtitle" />
              </div>
              {shape.badge ? (
                <div className="resource-card-badge">
                  <div className="skeleton-box skeleton-badge" />
                </div>
              ) : null}
            </div>
            {shape.status ? (
              <div className="resource-card-content">
                <div className="skeleton-box skeleton-status" />
              </div>
            ) : null}
            <div className="resource-card-meta">
              {shape.metaRows.map((width, rowIndex) => (
                <div
                  key={rowIndex}
                  className="skeleton-box skeleton-meta-row"
                  style={{ width: `${width}%` }}
                />
              ))}
            </div>
            <div className="resource-card-actions">
              {shape.actions.map((width, actionIndex) => (
                <div
                  key={actionIndex}
                  className="skeleton-box skeleton-action"
                  style={{ width: `${width}px` }}
                />
              ))}
            </div>
          </div>
        ))}
      </ResourceGrid>
    </div>
  )
}
