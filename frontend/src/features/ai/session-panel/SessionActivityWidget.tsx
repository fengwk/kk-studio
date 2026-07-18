import { getString, parsePayload } from '@/features/ai/session-event-payload'
import type { RootActivityDTO } from '@/shared/api/contracts'

/** Compact activity lines for the widget zone (not a multi-column tree panel). */
export function SessionActivityWidget({ activities }: { activities: RootActivityDTO[] }) {
  const lines = activities
    .map(activityLabel)
    .filter(Boolean)
    .slice(-8)
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
    case 'steer_requested':
      return '已提交 steer 指令'
    case 'follow_up_requested':
      return '已提交 follow-up'
    case 'abort_requested':
      return '已请求终止'
    default:
      return ''
  }
}
