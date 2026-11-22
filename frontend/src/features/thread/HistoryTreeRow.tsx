import { useI18n, type TranslationKey } from '@/shared/i18n'
import {
  historyRowSearchPreview,
  type HistoryEntryKind,
  type HistoryTreeRow,
} from '@/features/thread/history-tree'
import { historyLaneGlyphs } from '@/features/thread/history-tree-row-state'
import { SearchHitText } from '@/features/thread/SearchHitText'
import type { RefObject } from 'react'

const KIND_LABEL_KEYS: Record<HistoryEntryKind, TranslationKey> = {
  root: 'ai.chat.branch.kindRoot',
  user: 'ai.chat.history.user',
  assistant: 'ai.chat.history.assistant',
  tool: 'ai.chat.history.tool',
  custom: 'ai.chat.history.custom',
  notification: 'ai.chat.branch.kindNotification',
  other: 'ai.chat.history.system',
}

export interface HistoryTreeRowViewProps {
  row: HistoryTreeRow
  tokens: string[]
  dimmed: boolean
  selected: boolean
  /** 当前选中行的按钮引用，用于滚动到可见区。 */
  selectedRef: RefObject<HTMLButtonElement | null>
  onSelect: (entryId: string) => void
}

/**
 * 历史树的一行：kind + 可见文本预览，命中搜索时只高亮命中的片段。
 *
 * 预览按可用宽度单行省略，搜索时优先显示命中词；
 * 无障碍标签把结构事实（kind、是否在当前路径、是否 head）拼进名字里，并保留完整预览，
 * 因为视觉上这些事实与全文只由 lane 字形、颜色和省略呈现。
 */
export function HistoryTreeRowView({
  row,
  tokens,
  dimmed,
  selected,
  selectedRef,
  onSelect,
}: HistoryTreeRowViewProps) {
  const { t } = useI18n()
  const kindLabel = t(KIND_LABEL_KEYS[row.kind])
  return (
    <div role="listitem">
      <button
        type="button"
        aria-pressed={selected}
        aria-current={row.isHead ? 'true' : undefined}
        aria-label={t('ai.chat.history.entryAria', {
          kind: kindLabel,
          preview: row.preview,
          path: row.onActivePath ? ` · ${t('ai.chat.history.currentPath')}` : '',
          head: row.isHead ? ` · ${t('ai.chat.history.currentPosition')}` : '',
        })}
        data-lane={row.lane}
        data-fork={row.isFork ? 'true' : undefined}
        data-can-fork={row.canFork ? 'true' : 'false'}
        className={[
          'history-tree-entry',
          selected ? 'selected' : '',
          row.isHead ? 'head' : '',
          row.onActivePath ? 'on-path' : '',
          dimmed ? 'dimmed' : '',
        ].filter(Boolean).join(' ')}
        ref={selected ? selectedRef : undefined}
        onClick={() => onSelect(row.entry.entryId)}
      >
        <span className="history-tree-entry-cursor" aria-hidden="true">{selected ? '›' : ''}</span>
        <span className="history-tree-entry-lane" aria-hidden="true">{historyLaneGlyphs(row)}</span>
        {row.onActivePath ? <span className="history-tree-entry-path" aria-hidden="true">•</span> : null}
        <span className="history-tree-entry-kind">{kindLabel}</span>
        <span className="history-tree-entry-sep" aria-hidden="true">·</span>
        <span className="history-tree-entry-preview">
          <SearchHitText text={historyRowSearchPreview(row, tokens)} tokens={tokens} markClassName="history-tree-mark" />
        </span>
        {row.isHead ? (
          <span className="history-tree-entry-head">{t('ai.chat.history.currentPosition')}</span>
        ) : null}
      </button>
    </div>
  )
}
