import { Fragment } from 'react'
import {
  FOOTER_SEGMENT_SEPARATOR,
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import { useI18n } from '@/shared/i18n'

/** 只读事实 Footer；不承载任何 Agent/Model/Permission/Notification 交互。 */
export function ThreadStatusFooter(input: ThreadStatusModelInput) {
  const { t } = useI18n()
  const { segments } = buildThreadStatusModel(input)

  // 单行必须保留全部事实，超宽时由 CSS 省略；完整内容仍可通过 hover 读取。
  const lineTitle = segments.map((segment) => segment.title).filter(Boolean).join('\n')

  return (
    <footer className="thread-status-footer" aria-label={t('ai.runtime.thread.status')}>
      <div className="thread-status-line" title={lineTitle}>
        {segments.map((segment, index) => (
          <Fragment key={segment.key}>
            {index > 0 ? FOOTER_SEGMENT_SEPARATOR : null}
            <span>{segment.text}</span>
          </Fragment>
        ))}
      </div>
    </footer>
  )
}
