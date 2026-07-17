import type { RefObject } from 'react'
import { ChatRuntimeBarStatus, ChatSidebar } from '@/features/ai/ChatPanelParts'
import { ChatObservabilityPanel } from '@/features/ai/ChatObservabilityPanel'
import { SessionPanel } from '@/features/ai/session-panel'
import { TaskTimelinePanel } from '@/features/ai/TaskTimelinePanel'
import type { SubagentTaskNode } from '@/features/ai/subagent-task-tree'
import type { RelayPermission } from '@/features/ai/useHarnessTaskTimeline'
import type { SessionTimeline } from '@/features/ai/session-events'
import type {
  AgentDefinitionDTO,
  HarnessRunDTO,
  HarnessSessionDTO,
  ModelUsageSummaryDTO,
  RootActivityDTO,
  SessionYoloDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

/**
 * Harness adapter over the reusable SessionPanel shell.
 * Domain-specific chrome (sidebar, yolo/usage, task activity) is composed around the panel.
 */
export function ChatPanel({
  sessions,
  agentsById,
  activeSessionId,
  title,
  onBack,
  session,
  agent,
  timeline,
  runs,
  activeRun,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  pending,
  disabled,
  observability,
  taskTimeline,
  controlsPending,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onSteer,
  onFollowUp,
  onAbort,
}: {
  sessions: HarnessSessionDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  activeSessionId: string
  title: string
  onBack: () => void
  session?: HarnessSessionDTO
  agent?: AgentDefinitionDTO
  timeline: SessionTimeline
  runs: HarnessRunDTO[]
  activeRun: boolean
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  pending: boolean
  disabled: boolean
  observability: {
    yolo?: SessionYoloDTO
    usage?: ModelUsageSummaryDTO
    toolInvocations: ToolInvocationDTO[]
    observabilityError: unknown
    yoloPending: boolean
    decisionPending: boolean
    setYolo: (enabled: boolean) => void
    decideTool: (invocationId: string, decision: 'allow' | 'deny') => void
  }
  taskTimeline: {
    activities: RootActivityDTO[]
    taskTree: SubagentTaskNode[]
    relayPermissions: RelayPermission[]
    taskTimelineError: unknown
    taskTimelineLoading: boolean
    permissionDecisionPending: boolean
    decidePermission: (invocationId: string, decision: 'allow' | 'deny') => void
  }
  controlsPending: boolean
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onSteer: () => void
  onFollowUp: () => void
  onAbort: () => void
}) {
  const agentLabel = agent?.name || session?.agentDefinitionId || 'Agent'
  const runtimeProvider = timeline.runtimeContext.provider || agent?.defaultProviderName || '-'
  const runtimeModel = timeline.runtimeContext.model || agent?.defaultModelName || '-'
  const runtimeVariant = timeline.runtimeContext.variant || agent?.defaultVariant || '-'
  const pendingPermissions = observability.toolInvocations.filter(
    (invocation) => invocation.status === 'WAITING_APPROVAL',
  )
  const hasRelayPermissions = taskTimeline.relayPermissions.length > 0
  const showPermissionBanner = pendingPermissions.length > 0 || hasRelayPermissions

  return (
    <SessionPanel
      sidebar={
        <ChatSidebar
          sessions={sessions}
          agentsById={agentsById}
          activeSessionId={activeSessionId}
          title={title}
          onBack={onBack}
        />
      }
      title={agentLabel}
      subtitle={`${runtimeProvider} / ${runtimeModel} / ${runtimeVariant}`}
      status={<ChatRuntimeBarStatus runs={runs} activeRun={activeRun} />}
      messages={timeline.messages}
      messagesLoading={messagesLoading}
      messagesError={messagesError}
      bodyRef={bodyRef}
      draft={draft}
      composerDisabled={disabled}
      composerPending={pending}
      activeRun={activeRun}
      controlsPending={controlsPending}
      actionError={actionError}
      onDismissActionError={onDismissActionError}
      onDraftChange={onDraftChange}
      onSubmit={onSubmit}
      onSteer={onSteer}
      onFollowUp={onFollowUp}
      onAbort={onAbort}
      banner={
        showPermissionBanner ? (
          <div className="session-banner">
            <ChatObservabilityPanel
              yolo={observability.yolo}
              usage={undefined}
              toolInvocations={observability.toolInvocations}
              error={null}
              yoloPending={observability.yoloPending}
              decisionPending={observability.decisionPending}
              onYoloChange={observability.setYolo}
              onDecision={observability.decideTool}
              permissionsOnly
            />
            {!session?.parentSessionId && hasRelayPermissions ? (
              <TaskTimelinePanel
                activities={[]}
                taskTree={[]}
                relayPermissions={taskTimeline.relayPermissions}
                loading={false}
                error={null}
                decisionPending={taskTimeline.permissionDecisionPending}
                onDecision={taskTimeline.decidePermission}
                permissionsOnly
              />
            ) : null}
          </div>
        ) : null
      }
      footer={
        <>
          <ChatObservabilityPanel
            yolo={observability.yolo}
            usage={observability.usage}
            toolInvocations={observability.toolInvocations}
            error={observability.observabilityError}
            yoloPending={observability.yoloPending}
            decisionPending={observability.decisionPending}
            onYoloChange={observability.setYolo}
            onDecision={observability.decideTool}
            compact
          />
          {!session?.parentSessionId ? (
            <details className="session-activity-drawer">
              <summary>活动 / 子代理</summary>
              <TaskTimelinePanel
                activities={taskTimeline.activities}
                taskTree={taskTimeline.taskTree}
                relayPermissions={[]}
                loading={taskTimeline.taskTimelineLoading}
                error={taskTimeline.taskTimelineError}
                decisionPending={taskTimeline.permissionDecisionPending}
                onDecision={taskTimeline.decidePermission}
              />
            </details>
          ) : null}
        </>
      }
    />
  )
}
