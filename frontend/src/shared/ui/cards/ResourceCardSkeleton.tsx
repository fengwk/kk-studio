import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import './cards.css'

export interface ResourceCardSkeletonProps {
  /** 无障碍说明标签，默认“正在加载资源...” */
  label?: string
  className?: string
}

const FIXED_SKELETON_ITEMS = [0, 1, 2, 3, 4, 5]

/**
 * 资源卡列表骨架屏：
 * 采用 6 个固定 placeholder，卡片高度约 280px，结构与 ResourceCard 保持一致。
 * 在数据就绪前替代整行文字提示，在 prefers-reduced-motion 下自动关闭动效。
 */
export function ResourceCardSkeleton({
  label = '正在加载资源...',
  className,
}: ResourceCardSkeletonProps) {
  return (
    <div
      role="status"
      aria-busy="true"
      aria-label={label}
      className={['resource-skeleton-container', className].filter(Boolean).join(' ')}
    >
      <ResourceGrid>
        {FIXED_SKELETON_ITEMS.map((index) => (
          <div key={index} className="resource-card resource-card-skeleton" aria-hidden="true">
            <div className="resource-card-head">
              <div className="skeleton-box skeleton-icon" />
              <div className="resource-card-head-text">
                <div className="skeleton-box skeleton-title" />
                <div className="skeleton-box skeleton-subtitle" />
              </div>
            </div>
            <div className="resource-card-meta">
              <div className="skeleton-box skeleton-meta-row" />
              <div className="skeleton-box skeleton-meta-row" />
              <div className="skeleton-box skeleton-meta-row short" />
            </div>
            <div className="resource-card-actions">
              <div className="skeleton-box skeleton-action" />
              <div className="skeleton-box skeleton-action" />
            </div>
          </div>
        ))}
      </ResourceGrid>
      <span className="sr-only">{label}</span>
    </div>
  )
}
