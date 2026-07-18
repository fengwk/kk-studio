import type { RefObject } from 'react'
import { ChatSidebar } from '@/features/ai/ChatPanelParts'
import { ChatObservabilityPanel } from '@/features/ai/ChatObservabilityPanel'
import { SessionPanel } from '@/features/ai/session-panel'
import { SessionActivityWidget } from '@/features/ai/session-panel/SessionActivityWidget'
import { SessionStatusFooter } from '@/features/ai/session-panel/SessionStatusFooter'
import { SessionSubagentWidget } from '@/features/ai/session-panel/SessionSubagentWidget'
import type { SessionCommand } from '@/features/ai/session-panel/session-commands'
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
 * Harness adapter over SessionPanel.
 * Commands live in +// palette; footer is a pi-style status line (no YOLO checkbox).
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
  runtimeLabels,
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
  onCommand,
}: {
  sessions: HarnessSessionDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  activeSessionId: string
  title: string
  onBack: () => void
  session?: HarnessSessionDTO
  agent?: AgentDefinitionDTO
  timeline: SessionTimeline
  runtimeLabels?: {
    agentName: string
    providerName: string
    modelName: string
    variantName: string
    contextWindow?: number
  }
  runs?: HarnessRunDTO[]
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
  onCommand: (command: SessionCommand) => void
}) {
  const pendingPermissions = observability.toolInvocations.filter(
    (invocation) => invocation.status === 'WAITING_APPROVAL',
  )

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
      onCommand={onCommand}
      widgets={
        <>
          {!session?.parentSessionId ? (
            <>
              <SessionActivityWidget activities={taskTimeline.activities} />
              <SessionSubagentWidget taskTree={taskTimeline.taskTree} />
            </>
          ) : null}
          {pendingPermissions.length > 0 ? (
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
          ) : null}
          {!session?.parentSessionId && taskTimeline.relayPermissions.length > 0
            ? taskTimeline.relayPermissions.map((permission) => (
              <div key={permission.invocationId} className="session-permission-line">
                <strong>
                  子代理权限：
                  {permission.tool}
                </strong>
                <span>
                  {permission.workdir}
                  {' · '}
                  {permission.arguments}
                </span>
                <div className="session-permission-actions">
                  <button
                    type="button"
                    disabled={taskTimeline.permissionDecisionPending}
                    onClick={() => taskTimeline.decidePermission(permission.invocationId, 'deny')}
                  >
                    拒绝
                  </button>
                  <button
                    type="button"
                    disabled={taskTimeline.permissionDecisionPending}
                    onClick={() => taskTimeline.decidePermission(permission.invocationId, 'allow')}
                  >
                    允许
                  </button>
                </div>
              </div>
            ))
            : null}
        </>
      }
      footer={
        <SessionStatusFooter
          agentName={runtimeLabels?.agentName || agent?.name}
          providerName={runtimeLabels?.providerName || agent?.defaultProviderName}
          modelName={runtimeLabels?.modelName || agent?.defaultModelName}
          variantName={runtimeLabels?.variantName || agent?.defaultVariant}
          contextWindow={runtimeLabels?.contextWindow}
          yolo={observability.yolo}
          usage={observability.usage}
        />
      }
    />
  )
}
