import { Bot, ChevronRight, Cpu, Pencil, ServerCog, Trash2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'

type ResourceIcon = 'agent' | 'model' | 'provider'

export type ResourceCardRow =
  | {
      label: string
      value: string
      wrap?: boolean
    }
  | {
      label: string
      tags: string[]
      limit?: number
    }
  | {
      pairs: Array<{ label: string; value: string }>
    }
  | [string, string]

function isTagsRow(
  row: ResourceCardRow,
): row is { label: string; tags: string[]; limit?: number } {
  return !Array.isArray(row) && 'tags' in row
}

function isPairsRow(row: ResourceCardRow): row is { pairs: Array<{ label: string; value: string }> } {
  return !Array.isArray(row) && 'pairs' in row
}

function normalizeTextRow(
  row: Exclude<
    ResourceCardRow,
    { label: string; tags: string[]; limit?: number } | { pairs: Array<{ label: string; value: string }> }
  >,
): { label: string; value: string; wrap?: boolean } {
  if (Array.isArray(row)) {
    return { label: row[0], value: row[1] }
  }
  return row
}

function TagList({ tags, limit = 3 }: { tags: string[]; limit?: number }) {
  const clean = tags.map((item) => item.trim()).filter(Boolean)
  if (clean.length === 0) {
    return <span className="val val-empty" />
  }
  const visible = clean.slice(0, limit)
  const rest = clean.length - visible.length
  return (
    <div className="meta-chips meta-chips-single" title={clean.join(', ')}>
      {visible.map((name) => (
        <span key={name} className="meta-chip">
          {name}
        </span>
      ))}
      {rest > 0 ? <span className="meta-chip is-more">+{rest}</span> : null}
    </div>
  )
}

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
  rows: ResourceCardRow[]
  onStart?: () => void
  onEdit: () => void
  onDelete: () => void
  deletePending: boolean
}) {
  const { t } = useI18n()
  const Icon = icon === 'agent' ? Bot : icon === 'model' ? Cpu : ServerCog

  return (
    <article className="info-card">
      <div className="head">
        <div className="head-content">
          <div className="icon-box">
            <Icon aria-hidden="true" />
          </div>
          <div className="text-content">
            <h3 title={title}>{title}</h3>
            <p title={subtitle}>{subtitle}</p>
          </div>
        </div>
      </div>
      <div className="meta-block">
        {rows.map((row, index) => {
          if (isTagsRow(row)) {
            const tags = (row.tags ?? []).map((item) => item.trim()).filter(Boolean)
            // 空标签行仍保留 label，右侧留空（不显示 —）
            return (
              <div className="meta-row meta-row-tags" key={row.label}>
                <span className="lbl">{row.label}</span>
                <TagList tags={tags} limit={row.limit} />
              </div>
            )
          }
          if (isPairsRow(row)) {
            return (
              <div className="meta-pair-row" key={`pairs-${index}`}>
                {row.pairs.map((item) => (
                  <div className="meta-pair-item" key={item.label}>
                    <span className="lbl">{item.label}</span>
                    <span className="val" title={item.value}>
                      {item.value}
                    </span>
                  </div>
                ))}
              </div>
            )
          }
          const item = normalizeTextRow(row)
          return <MetaRow key={item.label} label={item.label} value={item.value} wrap={item.wrap} />
        })}
      </div>
      <div className="chat-card-foot split">
        {onStart && (
          <button
            className="action-enter-btn green"
            type="button"
            aria-label={`${t('ai.catalog.action.createSession')} ${title}`}
            onClick={onStart}
          >
            <ChevronRight aria-hidden="true" />
            {t('ai.catalog.action.createSession')}
          </button>
        )}
        <button
          className="action-enter-btn"
          type="button"
          aria-label={`${t('ai.catalog.action.edit')} ${title}`}
          onClick={onEdit}
        >
          <Pencil aria-hidden="true" />
          {t('ai.catalog.action.edit')}
        </button>
        <button
          className="action-enter-btn danger"
          type="button"
          aria-label={`${t('ai.catalog.action.delete')} ${title}`}
          onClick={onDelete}
          disabled={deletePending}
        >
          <Trash2 aria-hidden="true" />
          {t('ai.catalog.action.delete')}
        </button>
      </div>
    </article>
  )
}

function MetaRow({ label, value, wrap }: { label: string; value: string; wrap?: boolean }) {
  const empty = !value?.trim()
  return (
    <div className="meta-row">
      <span className="lbl">{label}</span>
      <span className={`val${wrap ? ' is-wrap' : ''}${empty ? ' val-empty' : ''}`} title={empty ? undefined : value}>
        {empty ? '' : value}
      </span>
    </div>
  )
}
