import type { RefObject } from 'react'
import { ChatSidebar } from '@/features/ai/ChatPanelParts'
import { ChatObservabilityPanel } from '@/features/ai/ChatObservabilityPanel'
import { ThreadPanel } from '@/features/ai/thread-panel'
import { ThreadActivityWidget } from '@/features/ai/thread-panel/ThreadActivityWidget'
import { ThreadStatusFooter } from '@/features/ai/thread-panel/ThreadStatusFooter'
import { ThreadSubagentWidget } from '@/features/ai/thread-panel/ThreadSubagentWidget'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import type { SubagentTaskNode } from '@/features/ai/subagent-task-tree'
import type { RelayPermission } from '@/features/ai/useHarnessTaskTimeline'
import type { ThreadTimeline } from '@/features/ai/thread-events'
import type {
  AgentDefinitionDTO,
  HarnessThreadDTO,
  ModelUsageSummaryDTO,
  RootActivityDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

/**
 * Harness adapter over ThreadPanel for AgentThread.
 * Commands live in the slash palette; footer is a pi-style status line.
 */
export function ChatPanel({
  threads,
  activeThreadId,
  sessionId,
  mainThreadId,
  title,
  onBack,
  agent,
  timeline,
  runtimeLabels,
  working,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  pending,
  disabled,
  observability,
  taskTimeline,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onCommand,
}: {
  threads: HarnessThreadDTO[]
  activeThreadId: string
  sessionId: string
  mainThreadId?: string
  title: string
  onBack: () => void
  agent?: AgentDefinitionDTO
  timeline: ThreadTimeline
  runtimeLabels?: {
    agentName: string
    providerName: string
    modelName: string
    variantName: string
    contextWindow?: number
  }
  working: boolean
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  pending: boolean
  disabled: boolean
  observability: {
    yolo?: { enabled: boolean }
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
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
}) {
  const pendingPermissions = observability.toolInvocations.filter(
    (invocation) => invocation.status === 'WAITING_APPROVAL',
  )

  return (
    <ThreadPanel
      sidebar={
        <ChatSidebar
          threads={threads}
          activeThreadId={activeThreadId}
          sessionId={sessionId}
          mainThreadId={mainThreadId}
          title={title}
          onBack={onBack}
        />
      }
      messages={timeline.messages}
      queuedMessages={timeline.queuedMessages}
      messagesLoading={messagesLoading}
      messagesError={messagesError}
      bodyRef={bodyRef}
      draft={draft}
      composerDisabled={disabled}
      composerPending={pending}
      working={working}
      actionError={actionError}
      onDismissActionError={onDismissActionError}
      onDraftChange={onDraftChange}
      onSubmit={onSubmit}
      onCommand={onCommand}
      widgets={
        <>
          <ThreadActivityWidget activities={taskTimeline.activities} />
          <ThreadSubagentWidget taskTree={taskTimeline.taskTree} />
          {pendingPermissions.length > 0 ? (
            <ChatObservabilityPanel
              yolo={undefined}
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
          {taskTimeline.relayPermissions.length > 0
            ? taskTimeline.relayPermissions.map((permission) => (
                <div key={permission.invocationId} className="thread-permission-line">
                  <strong>
                    子代理权限：
                    {permission.tool}
                  </strong>
                  <span>
                    {permission.workdir}
                    {' · '}
                    {permission.arguments}
                  </span>
                  <div className="thread-permission-actions">
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
        <ThreadStatusFooter
          agentName={runtimeLabels?.agentName || agent?.name}
          providerName={runtimeLabels?.providerName || agent?.defaultProviderName}
          modelName={runtimeLabels?.modelName || agent?.defaultModelName}
          variantName={runtimeLabels?.variantName || agent?.defaultVariant}
          contextWindow={runtimeLabels?.contextWindow}
          yoloEnabled={observability.yolo?.enabled}
          usage={observability.usage}
        />
      }
    />
  )
}
