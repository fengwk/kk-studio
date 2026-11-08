import { Badge, type BadgeTone } from '@/shared/ui/data/Badge'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { EnvironmentConfigState, EnvironmentConfigView } from './environment-utils'

const TONES: Record<EnvironmentConfigState, BadgeTone> = {
  applied: 'success',
  pendingApply: 'info',
  pendingConnect: 'warning',
  failed: 'danger',
  unknown: 'neutral',
}

const LABEL_KEYS: Record<EnvironmentConfigState, TranslationKey> = {
  applied: 'ai.environment.config.state.applied',
  pendingApply: 'ai.environment.config.state.pendingApply',
  pendingConnect: 'ai.environment.config.state.pendingConnect',
  failed: 'ai.environment.config.state.failed',
  unknown: 'ai.environment.config.state.unknown',
}

/** 配置状态与连接状态分开呈现：READY 不代表最新配置已经应用。 */
export function EnvironmentConfigStatusBadge({ view }: { view: EnvironmentConfigView }) {
  const { t } = useI18n()
  return <Badge tone={TONES[view.state]}>{t(LABEL_KEYS[view.state])}</Badge>
}
