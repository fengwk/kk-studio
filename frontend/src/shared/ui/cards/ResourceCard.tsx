import type { ReactNode } from 'react'
import './cards.css'

export interface ResourceCardPair {
  label: string
  value: string
}

/**
 * 资源卡元信息行，四种形态覆盖现有资源列表：
 * - `[label, value]` 简写文本行；
 * - 文本行对象（可选 wrap 两行截断）；
 * - 标签行（超出的标签折叠为 +N）；
 * - 成对字段行（如超时/idle，一行两项）。
 */
export type ResourceCardMetaRow =
  | [label: string, value: ReactNode]
  | { label: string; value?: ReactNode; wrap?: boolean }
  | { label: string; tags: string[]; limit?: number }
  | { pairs: ResourceCardPair[] }

export interface ResourceCardProps {
  /** 资源类型图标元素（通常为 20px 的 lucide 图标）；共享卡不绑定任何业务图标枚举。 */
  icon: ReactNode
  title: string
  subtitle?: string
  meta?: ResourceCardMetaRow[]
  /** 元信息之上的业务内容槽（正文、进度、附件等）。 */
  children?: ReactNode
  /** 底部动作槽；调用方用共享 Button/IconButton 组合业务动作，共享卡不内置任何业务动作。 */
  actions?: ReactNode
  /** 调用方作用域修饰类名。 */
  className?: string
}

function isTagsRow(
  row: ResourceCardMetaRow,
): row is { label: string; tags: string[]; limit?: number } {
  return !Array.isArray(row) && 'tags' in row
}

function isPairsRow(row: ResourceCardMetaRow): row is { pairs: ResourceCardPair[] } {
  return !Array.isArray(row) && 'pairs' in row
}

/** 空标签行仍保留 label，右侧留空，不显示伪占位符。 */
function TagList({ tags, limit = 3 }: { tags: string[]; limit?: number }) {
  const clean = tags.map((item) => item.trim()).filter(Boolean)
  if (clean.length === 0) {
    return <span className="resource-card-meta-value is-empty" />
  }
  const visible = clean.slice(0, limit)
  const rest = clean.length - visible.length
  return (
    <div className="resource-card-tags" title={clean.join(', ')}>
      {visible.map((name) => (
        <span key={name} className="resource-card-tag">
          {name}
        </span>
      ))}
      {rest > 0 ? <span className="resource-card-tag is-more">+{rest}</span> : null}
    </div>
  )
}

/** 只有 null/undefined/空白字符串算空值；`0` 与节点型值都由 React 原样呈现。 */
function isBlankMetaValue(value: ReactNode): boolean {
  if (value == null) {
    return true
  }
  return typeof value === 'string' && value.trim().length === 0
}

function TextValue({ value, wrap }: { value: ReactNode; wrap?: boolean }) {
  const empty = isBlankMetaValue(value)
  return (
    <span
      className={[
        'resource-card-meta-value',
        wrap ? 'is-wrap' : '',
        empty ? 'is-empty' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      // title 只对纯文本值有意义，ReactNode 值由调用方自带无障碍语义。
      title={!empty && typeof value === 'string' ? value : undefined}
    >
      {empty ? '' : value}
    </span>
  )
}

function MetaRow({ label, value, wrap }: { label: string; value: ReactNode; wrap?: boolean }) {
  return (
    <div className="resource-card-meta-row">
      <span className="resource-card-meta-label">{label}</span>
      <TextValue value={value} wrap={wrap} />
    </div>
  )
}

/**
 * 共享资源卡：统一资源列表的表面、边框/圆角、标题图标区、元信息与动作区。
 * 只做呈现——业务提供图标、标题、数据与动作，不在此绑定 AI 枚举或业务文案。
 */
export function ResourceCard({
  icon,
  title,
  subtitle,
  meta,
  children,
  actions,
  className,
}: ResourceCardProps) {
  return (
    <article className={['resource-card', className].filter(Boolean).join(' ')}>
      <div className="resource-card-head">
        <div className="resource-card-icon" aria-hidden="true">
          {icon}
        </div>
        <div className="resource-card-head-text">
          <h3 className="resource-card-title" title={title}>
            {title}
          </h3>
          {subtitle ? (
            <p className="resource-card-subtitle" title={subtitle}>
              {subtitle}
            </p>
          ) : null}
        </div>
      </div>
      {children !== undefined && children !== null ? (
        <div className="resource-card-content">{children}</div>
      ) : null}
      {meta && meta.length > 0 ? (
        <div className="resource-card-meta">
          {meta.map((row, index) => {
            if (isTagsRow(row)) {
              return (
                <div className="resource-card-meta-row is-tags" key={`tags-${row.label}`}>
                  <span className="resource-card-meta-label">{row.label}</span>
                  <TagList tags={row.tags} limit={row.limit} />
                </div>
              )
            }
            if (isPairsRow(row)) {
              return (
                <div className="resource-card-pairs" key={`pairs-${index}`}>
                  {row.pairs.map((pair) => (
                    <div className="resource-card-pair" key={pair.label}>
                      <span className="resource-card-pair-label">{pair.label}</span>
                      <span className="resource-card-pair-value" title={pair.value}>
                        {pair.value}
                      </span>
                    </div>
                  ))}
                </div>
              )
            }
            if (Array.isArray(row)) {
              return <MetaRow key={`row-${index}-${row[0]}`} label={row[0]} value={row[1]} />
            }
            return <MetaRow key={`row-${row.label}`} label={row.label} value={row.value} wrap={row.wrap} />
          })}
        </div>
      ) : null}
      {actions !== undefined && actions !== null ? (
        <div className="resource-card-actions">{actions}</div>
      ) : null}
    </article>
  )
}
