import type { CanvasFunctionRunStatus } from '@/shared/api/generated/contracts'
import type { TranslationKey } from '@/shared/i18n'

/** 运行状态在面板头部展示的 i18n key。 */
export function runStatusKey(status: CanvasFunctionRunStatus): TranslationKey {
  if (status === 'READY') {
    return 'canvas.generation.status.ready'
  }
  if (status === 'RUNNING') {
    return 'canvas.generation.status.running'
  }
  if (status === 'FAILED') {
    return 'canvas.generation.status.failed'
  }
  if (status === 'CANCELLED') {
    return 'canvas.generation.status.cancelled'
  }
  if (status === 'UNKNOWN') {
    return 'canvas.generation.status.unknown'
  }
  return 'canvas.generation.status.succeeded'
}

/** 运行反馈优先展示服务端 stage，UNKNOWN 时 stage 为空则回退到提示文案。 */
export function runDisplayLabel(
  t: (key: TranslationKey, values?: Record<string, string | number>) => string,
  status: CanvasFunctionRunStatus,
  stage: string,
): string {
  if (status === 'UNKNOWN') {
    return stage && stage !== status ? stage : t('canvas.generation.unknownStage')
  }
  return stage && stage !== status ? stage : t(runStatusKey(status))
}
