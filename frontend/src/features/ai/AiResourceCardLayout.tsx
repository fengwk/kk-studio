import { Bot, ChevronRight, Cpu, Pencil, ServerCog, Trash2 } from 'lucide-react'

type ResourceIcon = 'agent' | 'model' | 'provider'

export function ResourceCardLayout({
  icon,
  title,
  subtitle,
  rows,
  onStart,
  onEdit,
  onDelete,
  deletePending,
}: {
  icon: ResourceIcon
  title: string
  subtitle: string
  rows: Array<[string, string]>
  onStart?: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const Icon = icon === 'agent' ? Bot : icon === 'model' ? Cpu : ServerCog

  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <Icon aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3>{title}</h3>
            <p>{subtitle}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        {rows.map(([label, value]) => (
          <MetaRow key={label} label={label} value={value} />
        ))}
      </div>
      <div className="chat-card-foot split">
        {onStart && (
          <button className="action-enter-btn green" type="button" aria-label={`创建会话 ${title}`} onClick={onStart}>
            <ChevronRight aria-hidden="true" />
            创建会话
          </button>
        )}
        <button className="action-enter-btn" type="button" aria-label={`编辑 ${title}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          编辑
        </button>
        <button className="action-enter-btn danger" type="button" aria-label={`删除 ${title}`} onClick={onDelete} disabled={deletePending}>
          <Trash2 aria-hidden="true" />
          删除
        </button>
      </div>
    </article>
  )
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className="val">{value}</span>
    </div>
  )
}
