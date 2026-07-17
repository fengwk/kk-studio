import type { BackendLong, ModelUsageSummaryDTO, SessionYoloDTO, ToolInvocationDTO } from '@/shared/api/contracts'

export function ChatObservabilityPanel({
  yolo,
  usage,
  toolInvocations,
  error,
  yoloPending,
  decisionPending,
  onYoloChange,
  onDecision,
}: {
  yolo?: SessionYoloDTO
  usage?: ModelUsageSummaryDTO
  toolInvocations: ToolInvocationDTO[]
  error: unknown
  yoloPending: boolean
  decisionPending: boolean
  onYoloChange: (enabled: boolean) => void
  onDecision: (invocationId: string, decision: 'allow' | 'deny') => void
}) {
  const pendingDecisions = toolInvocations.filter((invocation) => invocation.status === 'WAITING_APPROVAL')

  return (
    <section className="chat-observability" aria-label="会话运行信息">
      <label className="yolo-toggle">
        <input
          type="checkbox"
          checked={yolo?.enabled ?? false}
          disabled={!yolo || yoloPending}
          onChange={(event) => onYoloChange(event.target.checked)}
        />
        <span>YOLO：自动批准工具调用</span>
      </label>
      <UsageSummary usage={usage} />
      {pendingDecisions.map((invocation) => (
        <PermissionRequest
          key={invocation.id}
          invocation={invocation}
          pending={decisionPending}
          onDecision={onDecision}
        />
      ))}
      {Boolean(error) && <span className="observability-error">部分运行信息加载失败。</span>}
    </section>
  )
}

function UsageSummary({ usage }: { usage?: ModelUsageSummaryDTO }) {
  if (!usage) {
    return <span className="usage-summary">Usage：加载中</span>
  }
  const tokenCount = integer(usage.inputTokens) + integer(usage.outputTokens)
  const cacheRead = integer(usage.cacheReadTokens)
  const totalCost = usage.costs.reduce((total, cost) => total + decimal(cost.total), 0)
  const currency = usage.costs[0]?.currency ?? ''
  return (
    <span className="usage-summary">
      Usage：{tokenCount} tokens（cache {cacheRead}）
      {integer(usage.recordCount) > 0 && ` · ${currency}${totalCost.toFixed(6)}`}
    </span>
  )
}

function PermissionRequest({
  invocation,
  pending,
  onDecision,
}: {
  invocation: ToolInvocationDTO
  pending: boolean
  onDecision: (invocationId: string, decision: 'allow' | 'deny') => void
}) {
  return (
    <div className="permission-request">
      <div>
        <strong>需要工具授权：{invocation.toolName}</strong>
        <span>
          {invocation.targetType}
          {invocation.environmentId ? ` / environment:${invocation.environmentId}` : ''}
        </span>
      </div>
      <div className="permission-actions">
        <button type="button" className="ghost-btn" disabled={pending} onClick={() => onDecision(invocation.id, 'deny')}>
          拒绝
        </button>
        <button type="button" className="btn-primary" disabled={pending} onClick={() => onDecision(invocation.id, 'allow')}>
          允许
        </button>
      </div>
    </div>
  )
}

function decimal(value: number | string): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function integer(value: BackendLong): number {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : 0
}
