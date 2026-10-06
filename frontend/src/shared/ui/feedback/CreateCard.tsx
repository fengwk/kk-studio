import { Plus } from 'lucide-react'

/** 共享“新建”卡片：以信息卡形态提供列表页的创建入口。 */
export function CreateCard({
  title,
  subtitle,
  onClick,
}: {
  title: string
  subtitle: string
  onClick: () => void
}) {
  return (
    <button className="info-card create-card" type="button" onClick={onClick} aria-label={title}>
      <span className="plus-icon">
        <Plus aria-hidden="true" />
      </span>
      <span>
        <strong>{title}</strong>
        <small>{subtitle}</small>
      </span>
    </button>
  )
}
