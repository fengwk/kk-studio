import { useContext, type ComponentPropsWithoutRef } from 'react'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import {
  MarkdownSegmentSourceContext,
  readMarkdownTableSource,
} from '@/shared/ui/markdown/markdownTableSource'
import { useI18n } from '@/shared/i18n'
import './markdown-table.css'

/** react-markdown 传入的 hast 节点，这里只用到源位置。 */
type MarkdownTableNode = {
  position?: { start?: { offset?: number | null }; end?: { offset?: number | null } }
}

/**
 * GFM 表格：原样保留 table/thead/tbody/th/td 与列对齐，
 * 外壳在表头上方预留紧凑复制位，复制的是本表格的原始 Markdown 片段。
 */
export function MarkdownTable({
  node,
  children,
  ...props
}: ComponentPropsWithoutRef<'table'> & { node?: MarkdownTableNode }) {
  const { t } = useI18n()
  const segmentSource = useContext(MarkdownSegmentSourceContext)
  const source = readMarkdownTableSource(segmentSource, node?.position)

  return (
    <div className="md-table-shell">
      {source === null ? null : (
        <CopyButton source={source} className="md-table-copy" label={t('shared.copyTable')} />
      )}
      <table {...props}>{children}</table>
    </div>
  )
}
