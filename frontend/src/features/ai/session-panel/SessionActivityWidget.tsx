import { getString, parsePayload } from '@/features/ai/session-event-payload'
import type { RootActivityDTO } from '@/shared/api/contracts'

const LIVE_ACTIVITY_TYPES = new Set([
  'subagent_started',
  'subagent_resumed',
  'subagent_completed',
  'subagent_cancel_requested',
  'permission_requested',
  'permission_resolved',
])

/**
 * Compact live activity lines for the widget zone.
 * Omits noisy control chatter (follow-up / steer / abort spam).
 */
export function SessionActivityWidget({ activities }: { activities: RootActivityDTO[] }) {
  const lines = activities
    .filter((activity) => LIVE_ACTIVITY_TYPES.has(activity.type))
    .map(activityLabel)
    .filter(Boolean)
    .slice(-6)
  if (lines.length === 0) {
    return null
  }
  return (
    <div className="session-activity-widget" aria-label="会话活动">
      <ul>
        {lines.map((line, index) => (
          <li key={`${line}-${index}`}>{line}</li>
        ))}
      </ul>
    </div>
  )
}

function activityLabel(activity: RootActivityDTO): string {
  const payload = parsePayload(activity.payloadJson)
  const childSessionId = getString(payload.childSessionId)
  const targetAgent = getString(payload.targetAgent)
  switch (activity.type) {
    case 'subagent_started':
      return `启动子代理 ${targetAgent || childSessionId}`
    case 'subagent_resumed':
      return `恢复子代理 ${targetAgent || childSessionId}`
    case 'subagent_completed':
      return `子代理完成：${getString(payload.status) || childSessionId}`
    case 'subagent_cancel_requested':
      return `请求取消子代理：${getString(payload.reason) || childSessionId}`
    case 'permission_requested':
      return `等待工具授权：${getString(payload.tool) || getString(payload.invocationId)}`
    case 'permission_resolved':
      return `工具授权已${getString(payload.decision)}`
    default:
      return ''
  }
}
