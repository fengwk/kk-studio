import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadStatusFooter,
  type ThreadCommand,
} from '@/features/ai/runtime'
import { THREAD_COMMANDS } from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
} from '@/features/ai/chat/SelectionListModal'
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
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type {
  CanvasDocumentDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { agentService } from '@/shared/api/agent-service'
import { sendCanvasThreadFirstSend } from '@/shared/api/studio-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/**
 * Canvas 空 Thread 的 slash 命令表：只有 agent/environment/yolo 可用
 * （compact 选择）；thread/session/tree/new/stop 保持可见但禁用。
 */
const CANVAS_BLANK_COMMANDS: ThreadCommand[] = THREAD_COMMANDS.map((command) => ({
  ...command,
  disabled: !['agent', 'environment', 'yolo'].includes(command.id),
  disabledReason: undefined,
  disabledReasonKey: ['agent', 'environment', 'yolo'].includes(command.id)
    ? undefined
    : 'ai.runtime.command.disabledReason',
}))

/**
 * Canvas 空 Thread（document.threadId == null）：
 * 提供 compact 的 Agent/Environment/YOLO 选择（ThreadStatusFooter + 选择弹层），
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
  // catalog 加载期间保持 draft 未冻结；第一个可 materialize 的 Agent 是
  // 确定性默认值（缺失/不可解析时面板显示明确错误并打开 agent picker）。
  const [frozenDraft, setFrozenDraft] = useState<BranchDraft | null>(null)
  const [parts, setParts] = useState<ComposerPart[]>([])
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
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
    setPending(true)
    setActionError(null)
    try {
      const result = await sendCanvasThreadFirstSend(canvasId, {
        commandId: crypto.randomUUID(),
        branchSettings: {
          environmentName: effective.environmentName,
          agentName: effective.agentName,
          model: { ...effective.model },
          activeTools: [...effective.activeTools],
        },
        yoloEnabled: effective.yoloEnabled,
        contents: partsToMessageContents(sendParts),
      })
      setParts([])
      onThreadBound(result.document)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
      setParts(localDraft)
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
      setAgentModalOpen(true)
      return
    }
    void runFirstSend(payload, localDraft, frozenDraft)
  }

  function handleCommand(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'agent':
        setAgentModalOpen(true)
        return
      case 'environment':
        setEnvironmentModalOpen(true)
        return
      case 'yolo':
        if (!pending) {
          setFrozenDraft((current) => current ? { ...current, yoloEnabled: !current.yoloEnabled } : current)
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
    setAgentModalOpen(false)
    setActionError(null)
  }

  function handleEnvironmentSelected(environmentName: string | null) {
    if (pending) {
      return
    }
    setFrozenDraft((current) => (current ? { ...current, environmentName } : current))
    setEnvironmentModalOpen(false)
    setActionError(null)
  }

  const environmentName = frozenDraft?.environmentName ?? null

  return (
    <>
      <section className="chat-shell thread-panel canvas-blank-thread">
        <main className="chat-main thread-panel-main">
          <div className="blank-pane-body">
            <h2>{t('canvas.agent.blankTitle')}</h2>
            <p>{t('canvas.agent.blankDescription')}</p>
            {actionError ? <div className="thread-error-panel">{actionError}</div> : null}
          </div>
          <ThreadComposer
            parts={parts}
            pending={pending}
            disabled={pending}
            onPartsChange={setParts}
            onSubmit={(payload, localDraft) => {
              handleSubmit(payload, localDraft)
            }}
            onCommand={handleCommand}
            commands={CANVAS_BLANK_COMMANDS}
          />
          <ThreadStatusFooter
            agentName={frozenDraft?.agentName || t('ai.runtime.action.blankAgent')}
            providerName={frozenDraft?.model.providerName || undefined}
            modelName={
              frozenDraft?.model.providerName && frozenDraft.model.modelName
                ? `${frozenDraft.model.providerName}/${frozenDraft.model.modelName}`
                : undefined
            }
            variantName={frozenDraft?.model.variant || undefined}
            environmentName={environmentName}
            environmentReady={
              environmentName == null
                ? undefined
                : (environmentReadyByName.get(environmentName) ?? false)
            }
            yoloEnabled={frozenDraft?.yoloEnabled ?? false}
            onAgentClick={() => setAgentModalOpen(true)}
            onEnvironmentClick={() => setEnvironmentModalOpen(true)}
          />
        </main>
      </section>
      <AgentSelectionModal
        open={agentModalOpen}
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => setAgentModalOpen(false)}
        onSelect={(selectedAgentName) => {
          handleAgentSelected(selectedAgentName)
        }}
      />
      <EnvironmentSelectionModal
        open={environmentModalOpen}
        environments={environments}
        selectedEnvironmentName={environmentName}
        selectionPending={pending}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(selectedName) => {
          handleEnvironmentSelected(selectedName)
        }}
      />
    </>
  )
}
