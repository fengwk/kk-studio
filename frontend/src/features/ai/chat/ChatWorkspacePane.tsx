import { AgentPane } from '@/features/ai/runtime/AgentPane'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { BranchRequestInput, PaneReport } from '@/features/ai/runtime/useRootThreadControl'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { ThreadPresentation } from '@/features/ai/runtime/thread-presentation'

/** Chat 只提供 owner/defaults；发送、导航、命令和 Thread projection 全部由 AgentPane 共享。 */
export function ChatWorkspacePane({
  chat,
  agents,
  environments = [],
  pane,
  focused,
  hidden = false,
  onValidateDraftName,
  onFocus,
  initialTarget,
  onTargetConsumed,
  onRequestBranch,
  onPaneReport,
  onPresentation,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  pane: { id: string }
  focused: boolean
  hidden?: boolean
  onValidateDraftName?: (target: PaneTarget, name: string) => Promise<string | null>
  onFocus: () => void
  initialTarget?: PaneTarget
  onTargetConsumed?: (target: PaneTarget) => void
  /** 新建分支请求（已绑定来源 pane id）：命名与目标 pane 由 workspace 统一决定。 */
  onRequestBranch?: (sourcePaneId: string, request: BranchRequestInput) => void
  /** 面板运行时摘要：workspace 只用它做目标路由与顶栏面包屑。 */
  onPaneReport?: (paneId: string, report: PaneReport) => void
  onPresentation?: (paneId: string, report: ThreadPresentation | null) => void
}) {
  return (
    <AgentPane
      owner={{ type: 'CHAT', chatId: chat.id }}
      paneId={pane.id}
      agents={agents}
      environments={environments}
      defaults={{
        agentName: chat.agentName,
        yoloEnabled: chat.yoloEnabled,
        environmentName: chat.environmentName,
      }}
      focused={focused}
      hidden={hidden}
      onValidateDraftName={onValidateDraftName}
      onFocus={onFocus}
      initialTarget={initialTarget}
      onTargetConsumed={onTargetConsumed}
      onRequestBranch={onRequestBranch == null
        ? undefined
        : (request) => onRequestBranch(pane.id, request)}
      onReport={onPaneReport == null
        ? undefined
        : (report) => onPaneReport(pane.id, report)}
      onPresentation={onPresentation == null ? undefined : (report) => onPresentation(pane.id, report)}
    />
  )
}
