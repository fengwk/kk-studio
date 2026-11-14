import type { ThreadModelRequestDebugTool } from '@/features/thread/runtime/thread-timeline-types'
import type { useI18n } from '@/shared/i18n'

/** 结构化 payload 的展示形态；不可序列化时退回字符串，不抛错。 */
export function formatJson(raw: unknown): string {
  if (raw == null) {
    return ''
  }
  try {
    return JSON.stringify(raw, null, 2) ?? ''
  } catch {
    return String(raw)
  }
}

/** 工具的环境支持级别；未知级别原样透出，便于调试未升级的前端。 */
export function formatEnvSupport(
  support: ThreadModelRequestDebugTool['environmentSupport'],
  t: ReturnType<typeof useI18n>['t'],
): string {
  switch (support) {
    case 'NONE':
      return t('ai.runtime.debug.envSupport.none')
    case 'ROUTED':
      return t('ai.runtime.debug.envSupport.routed')
    case 'REQUIRED':
      return t('ai.runtime.debug.envSupport.required')
    default:
      return support
  }
}
