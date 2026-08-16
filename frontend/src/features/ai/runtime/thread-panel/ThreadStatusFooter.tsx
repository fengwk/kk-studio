import {
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import { useI18n } from '@/shared/i18n'

/** 只读事实 Footer；不承载任何 Agent/Model/Permission/Notification 交互。 */
export function ThreadStatusFooter(input: ThreadStatusModelInput) {
  const { t } = useI18n()
  const model = buildThreadStatusModel(input)

  if (model.segments.length === 0) {
    return null
  }
  return (
    <footer className="thread-status-footer" aria-label={t('ai.runtime.thread.status')}>
      <div className="thread-status-line">
        {model.segments.map((segment) => (
          <span key={segment.key} className={`thread-status-unit ${segment.className}`}>
            <span className="thread-status-seg" title={segment.title}>
              {segment.text}
            </span>
          </span>
        ))}
      </div>
    </footer>
  )
}
