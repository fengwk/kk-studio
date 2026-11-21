import type { ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'

/** 编辑器区块标识：模型与 Agent 各自的区块顺序由外层决定，这里只定义稳定名字。 */
export type CatalogSectionName =
  | 'basics'
  | 'limits'
  | 'presets'
  | 'pricing'
  | 'execution'
  | 'prompt'
  | 'capabilities'

/**
 * 编辑器区块：带稳定 id 的跳转目标 + 区块标题。
 * 只描述分组，不创建滚动区域；单滚动区与区块导航由编辑器弹窗外壳负责。
 */
export function CatalogEditorSection({
  name,
  children,
}: {
  name: CatalogSectionName
  children: ReactNode
}) {
  const { t } = useI18n()
  const targetId = `catalog-section-${name}`
  const headingId = `${targetId}-heading`

  return (
    <section
      className="catalog-editor-section"
      id={targetId}
      aria-labelledby={headingId}
      tabIndex={-1}
    >
      <h3 className="catalog-editor-section-heading" id={headingId}>
        {t(`ai.catalog.section.${name}`)}
      </h3>
      {children}
    </section>
  )
}
