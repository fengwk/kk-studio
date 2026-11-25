import '@/shared/ui/cards/CreateCard.css'
import { Plus } from 'lucide-react'

/** 共享“新建”卡片：与资源卡同一尺寸语言的虚线入口，样式见 styles.css 的 .create-card。 */
export function CreateCard({
  title,
  subtitle,
  onClick,
  compact = false,
}: {
  title: string
  subtitle?: string
  onClick: () => void
  compact?: boolean
}) {
  return (
    <button className="create-card" data-compact={compact || undefined} type="button" onClick={onClick} aria-label={title}>
      <span className="create-card-icon" aria-hidden="true">
        <Plus />
      </span>
      <span className="create-card-text">
        <strong>{title}</strong>
        {subtitle && <small>{subtitle}</small>}
      </span>
    </button>
  )
}
