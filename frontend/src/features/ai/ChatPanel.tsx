import type { RefObject } from 'react'
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
  ModelUsageSummaryDTO,
  RootActivityDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

/**
 * Pane-scoped Thread adapter over ThreadPanel.
 * No permanent Session/Thread sidebar; agent/model labels come from Thread DTO.
 */
export function ChatPanel({
  timeline,
  runtimeLabels,
  working,
  retryPresentation,
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
  commands,
}: {
  timeline: ThreadTimeline
  runtimeLabels?: {
    agentName: string
    providerName: string
    modelName: string
    variantName: string
    contextWindow?: number
  }
  working: boolean
  retryPresentation?: {
    workingLabel: string | null
    stoppedNotice: string | null
    queueLabel: string
  }
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
  commands?: ThreadCommand[]
}) {
  const pendingPermissions = observability.toolInvocations.filter(
    (invocation) => invocation.status === 'WAITING_APPROVAL',
  )

  return (
    <ThreadPanel
      messages={timeline.messages}
      queuedMessages={timeline.queuedMessages}
      messagesLoading={messagesLoading}
      messagesError={messagesError}
      bodyRef={bodyRef}
      draft={draft}
      composerDisabled={disabled}
      composerPending={pending}
      working={working}
      retryPresentation={retryPresentation}
      actionError={actionError}
      onDismissActionError={onDismissActionError}
      onDraftChange={onDraftChange}
      onSubmit={onSubmit}
      onCommand={onCommand}
      commands={commands}
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
          agentName={runtimeLabels?.agentName}
          providerName={runtimeLabels?.providerName}
          modelName={runtimeLabels?.modelName}
          variantName={runtimeLabels?.variantName}
          contextWindow={runtimeLabels?.contextWindow}
          yoloEnabled={observability.yolo?.enabled}
          usage={observability.usage}
        />
      }
    />
  )
}
