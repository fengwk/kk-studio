import {
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import { useI18n } from '@/shared/i18n'

/** 只读事实 Footer；不承载任何 Agent/Model/Permission/Notification 交互。 */
export function ThreadStatusFooter(input: ThreadStatusModelInput) {
  const { t } = useI18n()
  const model = buildThreadStatusModel(input)

  const envSegment = model.segments.find((segment) => segment.key === 'environment')
  const contextSegment = model.segments.find((segment) => segment.key === 'context')
  const usageSegment = model.segments.find((segment) => segment.key === 'usage')

  const metaText = [envSegment?.text, contextSegment?.text].filter(Boolean).join(' · ')
  const metaTitle = [envSegment?.title, contextSegment?.title].filter(Boolean).join(' · ')
  const usageText = usageSegment?.text ?? ''
  const usageTitle = usageSegment?.title ?? ''

  return (
    <footer className="thread-status-footer" aria-label={t('ai.runtime.thread.status')}>
      <div className="thread-status-line thread-status-line-meta" title={metaTitle}>
        {metaText}
      </div>
      <div className="thread-status-line thread-status-line-usage" title={usageTitle}>
        {usageText}
      </div>
    </footer>
  )
}
