import { useMemo } from 'react'
import {
  buildThreadStatusModel,
  type ThreadStatusModelInput,
} from '@/features/ai/runtime/thread-panel/thread-status-format'
import { ThreadStatusSegmentRows } from '@/features/ai/runtime/thread-panel/thread-status-segments'
import { useI18n } from '@/shared/i18n'

/**
 * 页脚：agent | model | usage
 *
 * 仅负责组合：构建稳定的纯文本状态模型，并把得到的分段
 * 转发给响应式行布局。纯格式化逻辑在
 * {@link buildThreadStatusModel} 中；布局/排版逻辑在 {@link ThreadStatusSegmentRows} 中。
 */
export function ThreadStatusFooter({
  agentName,
  providerName,
  modelName,
  variantName,
  environment,
  environmentReady,
  yoloEnabled,
  onAgentClick,
  onModelClick,
  onVariantClick,
  onEnvironmentClick,
  notificationsEnabled,
  notificationPermission,
  onNotificationsToggle,
}: ThreadStatusModelInput) {
  const { t, locale } = useI18n()
  const model = useMemo(
    () => {
      void locale
      return buildThreadStatusModel({
        agentName,
        providerName,
        modelName,
        variantName,
        environment,
        environmentReady,
        yoloEnabled,
        onAgentClick,
        onModelClick,
        onVariantClick,
        onEnvironmentClick,
        notificationsEnabled,
        notificationPermission,
        onNotificationsToggle,
      })
    },
    [
      locale,
      agentName,
      providerName,
      modelName,
      variantName,
      environment,
      environmentReady,
      yoloEnabled,
      onAgentClick,
      onModelClick,
      onVariantClick,
      onEnvironmentClick,
      notificationsEnabled,
      notificationPermission,
      onNotificationsToggle,
    ],
  )

  return (
    <footer className="thread-status-footer" aria-label={t('ai.runtime.thread.status')}>
      <ThreadStatusSegmentRows segments={model.segments} />
    </footer>
  )
}
