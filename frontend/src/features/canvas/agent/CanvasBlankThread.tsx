import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadShortcutsPanel,
  ThreadStatusFooter,
  type ThreadCommand,
} from '@/features/ai/runtime'
import { threadCommandsForScene } from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  AgentSelectionPanel,
} from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import { useEnvironmentWorkspaceMetadata } from '@/features/ai/environment/useEnvironmentWorkspaceMetadata'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import {
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import { toAgentModelViews } from '@/features/ai/catalog'
import {
  hasMessageContent,
  partsToMessageContents,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  clearStoredComposerDraft,
  restoreComposerDraft,
  storeComposerDraft,
} from '@/features/ai/composer/composer-draft'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import type {
  CanvasDocumentDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { agentService } from '@/shared/api/agent-service'
import { sendCanvasThreadFirstSend } from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/**
 * Canvas 空 Thread 的 slash 命令表：复用统一场景投影（canvas-blank 场景无
 * Chat-scoped Thread picker，因此 /thread 保持禁用）。
 */
const CANVAS_BLANK_COMMANDS: ThreadCommand[] = threadCommandsForScene('canvas-blank')

/**
 * Canvas 空 Thread（document.threadId == null）：
 * 提供共享双层 Composer 的 Model/Variant/Permission 控件，以及 Agent/Environment 内联选择面板；
 * ThreadStatusFooter 只展示真实 Environment/Workspace/Git facts。
 * 首次发送走 canvas-scoped 原子端点 POST /canvases/{id}/thread/messages
 * （branch settings + 有序 USER_MESSAGE contents），成功后把携带 threadId 的
 * document 交还 controller，由绑定 Thread 接管面板。
 */
export function CanvasBlankThread({
  canvasId,
  agents,
  environments = [],
  onThreadBound,
}: {
  canvasId: UUIDString
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  onThreadBound: (document: CanvasDocumentDTO) => void
}) {
  const { t } = useI18n()
  const draftStorageScope = `canvas:${canvasId}`
  // catalog 加载期间保持 draft 未冻结；第一个可 materialize 的 Agent 是
  // 确定性默认值（缺失/不可解析时面板显示明确错误并打开 agent picker）。
  const [frozenDraft, setFrozenDraft] = useState<BranchDraft | null>(null)
  const [parts, setPartsState] = useState<ComposerPart[]>(
    () => restoreComposerDraft(draftStorageScope, []),
  )
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [interaction, setInteraction] = useState<'agent' | 'environment' | 'shortcuts' | null>(null)
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models = useMemo(
    () => toAgentModelViews(modelsQuery.data?.results ?? []),
    [modelsQuery.data],
  )
  const environmentReadyByName = useMemo(
    () => new Map(environments.map((environment) => [environment.name, environment.ready])),
    [environments],
  )

  useEffect(() => {
    if (frozenDraft != null || agents.length === 0 || modelsQuery.isLoading) {
      return
    }
    const agent = agents.find((candidate) => materializeBlankBranchDraft(candidate, false, models) != null)
    const materialized = agent ? materializeBlankBranchDraft(agent, false, models) : null
    if (materialized != null) {
      setFrozenDraft(materialized)
    }
  }, [agents, frozenDraft, models, modelsQuery.isLoading])

  async function runFirstSend(
    sendParts: ComposerPart[],
    localDraft: ComposerPart[],
    effective: BranchDraft,
  ) {
    clearStoredComposerDraft(draftStorageScope)
    setPending(true)
    setActionError(null)
    try {
      const result = await sendCanvasThreadFirstSend(canvasId, {
        commandId: crypto.randomUUID(),
        branchSettings: {
          environment: effective.environment
            ? { name: effective.environment.name, workspacePath: effective.environment.workspacePath }
            : null,
          agentName: effective.agentName,
          model: { ...effective.model },
          activeTools: [...effective.activeTools],
        },
        yoloEnabled: effective.yoloEnabled,
        contents: partsToMessageContents(sendParts),
      })
      setPartsState([])
      onThreadBound(result.document)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
      updateParts(localDraft)
    } finally {
      setPending(false)
    }
  }

  function handleSubmit(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) {
    const payload = trimMessageParts(payloadParts ?? parts)
    const localDraft = trimMessageParts(localDraftParts ?? parts)
    if (!hasMessageContent(payload) || slashQueryOf(payload) != null || pending) {
      return
    }
    if (!frozenDraft) {
      // catalog 未加载或 Agent/model 无法解析：打开 picker 补全 draft。
      setActionError(t('canvas.agent.agentMissing'))
      setInteraction('agent')
      return
    }
    void runFirstSend(payload, localDraft, frozenDraft)
  }

  function updateParts(next: ComposerPart[]) {
    storeComposerDraft(draftStorageScope, next)
    setPartsState(next)
  }

  function handleCommand(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'agent':
        setInteraction('agent')
        return
      case 'environment':
        setInteraction('environment')
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      case 'yolo':
        if (!pending) {
          setYoloEnabled(!(frozenDraft?.yoloEnabled ?? false))
        }
        return
      default:
        setActionError(
          command.disabledReason
          || t('ai.runtime.action.unavailableScene', { command: command.id }),
        )
    }
  }

  function handleAgentSelected(selectedAgentName: string) {
    const agent = agents.find((item) => item.name === selectedAgentName)
    const next = agent != null ? materializeAgentBranchDraft(agent, models, frozenDraft) : null
    if (next == null) {
      setActionError(t('ai.runtime.action.agentUnresolvable', { agent: selectedAgentName }))
      return
    }
    setFrozenDraft(next)
    setInteraction(null)
    setActionError(null)
  }

  function handleEnvironmentSelected(environment: EnvironmentBindingDTO | null) {
    if (pending) {
      return
    }
    setFrozenDraft((current) => (current ? { ...current, environment } : current))
    setInteraction(null)
    setActionError(null)
  }

  function setYoloEnabled(enabled: boolean) {
    if (pending) {
      return
    }
    setFrozenDraft((current) => current ? { ...current, yoloEnabled: enabled } : current)
    setActionError(null)
  }

  const environment = frozenDraft?.environment ?? null
  const environmentReady =
    environment == null
      ? undefined
      : (environmentReadyByName.get(environment.name) ?? false)
  const { gitBranch } = useEnvironmentWorkspaceMetadata(environment, environmentReady)
  const draftResolutionError =
    actionError == null
    && !modelsQuery.isLoading
    && frozenDraft == null
    && !agents.some((agent) => materializeBlankBranchDraft(agent, false, models) != null)
      ? t('canvas.agent.agentMissing')
      : null
  const interactionPanel =
    interaction === 'agent' ? (
      <AgentSelectionPanel
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        selectedAgentName={frozenDraft?.agentName}
        selectionPending={pending}
        onClose={() => setInteraction(null)}
        onSelect={handleAgentSelected}
      />
    ) : interaction === 'environment' ? (
      <EnvironmentWorkspacePanel
        environments={environments}
        current={environment}
        pending={pending}
        onClose={() => setInteraction(null)}
        onSelect={handleEnvironmentSelected}
      />
    ) : interaction === 'shortcuts' ? (
      <ThreadShortcutsPanel onClose={() => setInteraction(null)} />
    ) : null

  return (
    <section className="chat-shell thread-panel canvas-blank-thread">
      <main className="chat-main thread-panel-main">
        <div className="blank-pane-body">
          <h2>{t('canvas.agent.blankTitle')}</h2>
          <p>{t('canvas.agent.blankDescription')}</p>
          {actionError || draftResolutionError ? (
            <div className="thread-error-panel">{actionError ?? draftResolutionError}</div>
          ) : null}
        </div>
        <ThreadComposer
          parts={parts}
          pending={pending}
          disabled={pending}
          onPartsChange={updateParts}
          onSubmit={(payload, localDraft) => {
            handleSubmit(payload, localDraft)
          }}
          onCommand={handleCommand}
          commands={CANVAS_BLANK_COMMANDS}
          focusOnEscape={interactionPanel == null}
          active={interactionPanel == null}
          settings={frozenDraft == null ? undefined : {
            model: frozenDraft.model,
            models,
            yoloEnabled: frozenDraft.yoloEnabled,
            onModelChange: (model) => {
              setFrozenDraft((current) => current ? { ...current, model } : current)
              setActionError(null)
            },
            onYoloChange: setYoloEnabled,
          }}
        />
        {interactionPanel}
        <ThreadStatusFooter
          environment={environment}
          environmentReady={environmentReady}
          gitBranch={gitBranch}
        />
      </main>
    </section>
  )
}
