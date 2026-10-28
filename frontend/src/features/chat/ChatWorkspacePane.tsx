import { AgentPane } from '@/features/chat/runtime/AgentPane'
import type { BranchRequestInput, PaneReport } from '@/features/chat/runtime/root-thread/types'
import type { PaneTarget } from '@/features/chat/runtime/agent-pane'
import type { ThreadPresentation } from '@/features/chat/runtime/thread-presentation'
import type {
  AgentDefinitionDTO,
  ChatDTO,
  EnvironmentCardDTO,
} from '@/shared/api/generated/contracts'

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
  target,
  onTargetChange,
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
  /** 由 ChatWorkspacePage 的 tab 呈现状态唯一持有。 */
  target: PaneTarget
  onTargetChange: (target: PaneTarget) => void
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
      target={target}
      onTargetChange={onTargetChange}
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
