import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { useAccount } from '@/features/account/account-context'
import { clearStoredComposerDraft } from '@/features/thread/composer/composer-draft'
import { partsToMessageContents, type ComposerPart } from '@/features/thread/composer/composer-parts'
import { RootThreadControlArea } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { fetchThreadSnapshot } from '@/features/thread/runtime/state/thread-snapshot-cache'
import { ThreadPanel, ThreadStatusFooter } from '@/features/thread/runtime/thread-panel'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { PaneRendererProps } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { agentService } from '@/shared/api/agent-service'
import { ApiError, apiErrorDetail, request } from '@/shared/api/client'
import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import type { HarnessModelSelectionDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { Button } from '@/shared/ui/controls/Button'
import { usePaneDraft } from './usePaneDraft'
import './thread-workspace.css'

type Props = PaneRendererProps<{ kind: 'draft'; draftId: string; folderId?: string }>
const draftCommands = THREAD_COMMANDS.filter(({ id }) => id === 'upload')

export function DraftPaneRenderer(props: Props) {
  return <DraftPane key={`${props.space.id}:${props.target.draftId}`} {...props} />
}

function DraftPane({ active, onUnsentChange, openSpaceSettings, replace, space, target }: Props) {
  const { t } = useI18n()
  const { user } = useAccount()
  const cache = useQueryClient()
  const bodyRef = useRef<HTMLDivElement>(null)
  const scope = `workspace-draft:${target.draftId}`
  const [parts, changeParts] = usePaneDraft(scope, onUnsentChange)
  const [model, setModel] = useState<HarnessModelSelectionDTO>()
  const [environmentName, setEnvironmentName] = useState<string | null>(null)
  const [yolo, setYolo] = useState<boolean>()
  const agents = useQuery({ queryKey: queryKeys.agents.list, queryFn: agentService.listAgents })
  const models = useQuery({ queryKey: queryKeys.models.list, queryFn: agentService.listModels })
  const environments = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => request('GET /api/platform/environments', {}),
  })
  const agent = agents.data?.find(({ name, execution }) => name === space.defaultAgentName && execution != null)
  const modelSelection = model ?? (agent ? getBuiltinModelSelection(agent.execution) : null)
  const environment = environments.data?.find(({ id, name }) => environmentName == null
    ? id === space.defaultEnvironmentId : name === environmentName)
  const environmentLabel = space.defaultEnvironmentId ? t('ai.runtime.draft.environmentDefault') : undefined
  const yoloEnabled = yolo ?? space.defaultYoloEnabled

  const send = useMutation({
    mutationFn: (payload: ComposerPart[]) => request('POST /api/chat-spaces/{spaceId}/sessions', {
        path: { spaceId: space.id },
        body: {
          folderId: target.folderId,
          message: partsToMessageContents(payload),
          modelSelection,
          environmentId: environmentName == null ? undefined : environment?.id,
          yoloEnabled,
        },
      }, { idempotencyKey: target.draftId }),
    onSuccess: ({ threadId }) => {
      clearStoredComposerDraft(scope)
      // 接受后读取服务端快照，不制造临时历史；首条命令在快照中是排队项或已物化的消息。
      void cache.prefetchQuery({
        queryKey: queryKeys.threads.snapshot(threadId),
        queryFn: ({ signal }) => fetchThreadSnapshot(cache, threadId, { signal }),
      })
      void cache.invalidateQueries({ queryKey: workspaceKeys.tree(space.id) })
    },
  })

  const notice = agents.isError
    ? <div className="thread-draft-notice" role="alert">
      {apiErrorDetail(agents.error) ?? t('shared.requestFailed')}
      <Button variant="secondary" onClick={() => void agents.refetch()}>{t('ai.interaction.retry')}</Button>
    </div>
    : agents.data && !agent && <div className="thread-draft-notice" role="status">
      {t(agents.data.length ? 'ai.runtime.draft.defaultAgentMissing' : 'ai.chat.noAgents')}
      {agents.data.length > 0
        ? <Button variant="secondary" onClick={openSpaceSettings}>{t('ai.runtime.draft.spaceSettings')}</Button>
        : user?.role === 'ADMIN' && <Link to="/settings/agents">{t('ai.runtime.draft.agentSettings')}</Link>}
    </div>
  const actionError = send.error instanceof ApiError && (send.error.reason === 'INVALID_FOLDER' || send.error.code === 'INVALID_FOLDER')
    ? t('ai.runtime.draft.invalidFolder')
    : send.error ? apiErrorDetail(send.error) ?? send.error.message : null

  return <ThreadPanel
    transcript={{ messages: [], queuedMessages: [], loading: false, error: null, bodyRef }}
    activity={{ working: false, actionError, onDismissActionError: send.reset }}
    controls={<RootThreadControlArea rootThreadId={null} messages={[]} queuedMessages={[]} notice={notice}
      composer={{
        parts,
        pending: send.isPending,
        disabled: send.isPending,
        sendDisabled: !agent || agents.isError,
        focusOnEscape: active,
        scope,
        onPartsChange: changeParts,
        onSubmit: (payload) => send.mutate(payload, {
          onSuccess: ({ threadId }) => {
            changeParts([])
            onUnsentChange(false)
            replace({ kind: 'thread', threadId })
          },
        }),
        commands: draftCommands,
        onCommand: () => {},
        settings: {
          modelSelection,
          models: models.data ?? [],
          environmentName,
          defaultEnvironmentLabel: environmentName == null ? environment?.name ?? environmentLabel : environmentLabel,
          environments: environments.data ?? [],
          yoloEnabled,
          onModelChange: setModel,
          onEnvironmentChange: setEnvironmentName,
          onYoloChange: setYolo,
        },
      }} />}
    slots={{ footer: <ThreadStatusFooter
      environment={environment ? { environmentId: environment.id, environmentName: environment.name } : null}
      environmentReady={environment?.ready} /> }}
  />
}
