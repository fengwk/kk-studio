import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import { Link } from 'react-router'
import type { ThreadPresentation } from './thread-presentation'

export function ThreadPresentationActions({ view }: { view: ThreadPresentation | null }) {
  const { t } = useI18n()
  if (!view) {
    return null
  }
  return (
    <>
      {view.parentThreadId ? view.parentHref
        ? <Link className="ghost-btn is-compact" to={view.parentHref}>{t('ai.runtime.childThread.back')}</Link>
        : <Button variant="ghost" size="compact"
          onClick={() => view.act(view.viewKey, 'parent')}>{t('ai.runtime.childThread.back')}</Button> : null}
      {view.parentThreadId && view.identity
        ? <span className="workspace-view-identity" title={view.identity}>{view.identity}</span>
        : null}
    </>
  )
}
