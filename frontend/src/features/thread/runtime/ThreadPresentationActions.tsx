import { Button } from '@/shared/ui/controls/Button'
import { CopyTextButton } from '@/shared/ui/controls/CopyTextButton'
import { useI18n } from '@/shared/i18n'
import { Link } from 'react-router'
import { shortThreadId, type ThreadPresentation } from './thread-presentation'

/**
 * 子代理视图顶栏动作：返回父 agent，身份（agent · model），再补前 8 位 thread ID 与复制按钮。
 *
 * 短 ID 只做展示：悬停提示与复制都使用完整 threadId，路由仍由返回入口的完整地址决定。
 */
export function ThreadPresentationActions({ view }: { view: ThreadPresentation | null }) {
  const { t } = useI18n()
  if (!view) {
    return null
  }
  const subagentThreadId = view.parentThreadId ? view.threadId : null
  return (
    <>
      {view.parentThreadId ? view.parentHref
        ? <Link className="ghost-btn is-compact" to={view.parentHref}>{t('ai.runtime.childThread.back')}</Link>
        : <Button variant="ghost" size="compact"
          onClick={() => view.act(view.viewKey, 'parent')}>{t('ai.runtime.childThread.back')}</Button> : null}
      {view.parentThreadId && view.identity
        ? <span className="workspace-view-identity" title={view.identity}>{view.identity}</span>
        : null}
      {subagentThreadId ? (
        <>
          <span className="workspace-view-short-id" title={subagentThreadId}>
            {shortThreadId(subagentThreadId)}
          </span>
          <CopyTextButton
            source={subagentThreadId}
            label={t('ai.runtime.childThread.copyThreadId')}
            copiedLabel={t('ai.runtime.childThread.threadIdCopied')}
            failedLabel={t('ai.runtime.childThread.copyThreadIdFailed')}
          />
        </>
      ) : null}
    </>
  )
}
