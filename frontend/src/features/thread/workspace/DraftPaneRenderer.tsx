import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { useAccount } from '@/features/account/account-context'
import { clearStoredComposerDraft, restoreComposerDraft } from '@/features/thread/composer/composer-draft'
import { partsToMessageContents, trimMessageParts, type ComposerPart } from '@/features/thread/composer/composer-parts'
import { RootThreadControlArea } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { fetchThreadSnapshot } from '@/features/thread/runtime/state/thread-snapshot-cache'
import { ThreadPanel, ThreadStatusFooter } from '@/features/thread/runtime/thread-panel'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { PaneRendererProps } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { ApiError, apiErrorDetail, isConflictReason, request } from '@/shared/api/client'
import type { HarnessModelSelectionDTO, SessionCreateDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { clearSessionSend, loadSessionSend, saveSessionSend, type SessionSendRecord } from './session-send-record'
import { useComposerModels } from './useComposerModels'
import { usePaneDraft } from './usePaneDraft'
import './thread-workspace.css'

type Props = PaneRendererProps<{ kind: 'draft'; draftId: string; folderId?: string }>
const draftCommands = THREAD_COMMANDS.filter(({ id }) => id === 'upload')

export function DraftPaneRenderer(props: Props) {
  const { user } = useAccount()
  if (!user) return null
  const storageKey = `kk-studio.session-send:workspace-draft:${JSON.stringify([user.id, props.space.id, props.target.draftId])}`
  return <DraftPane key={storageKey} {...props} storageKey={storageKey} />
}

function DraftPane({ active, onUnsentChange, openSpaceSettings, replace, space, target, storageKey }: Props & { storageKey: string }) {
  const { t } = useI18n()
  const { user } = useAccount()
  const cache = useQueryClient()
  const bodyRef = useRef<HTMLDivElement>(null)
  const scope = `workspace-draft:${target.draftId}`
  const [stored, setStored] = useState(() => {
    try {
      return { record: loadSessionSend(storageKey), unreadable: false }
    } catch {
      return { record: null, unreadable: true }
    }
  })
  const [storageError, setStorageError] = useState<string | null>(null)
  const sending = useRef(false)
  const retryRecord = !stored.unreadable && stored.record && !stored.record.value.rejected ? stored.record : null
  const { parts, changeParts, clearParts } = usePaneDraft(scope, onUnsentChange, retryRecord != null)
  const [model, setModel] = useState<HarnessModelSelectionDTO>()
  const [environmentName, setEnvironmentName] = useState<string | null>(null)
  const [yolo, setYolo] = useState<boolean>()
  const agents = useQuery({
    queryKey: ['agents', 'summaries'],
    queryFn: ({ signal }) => request('GET /api/platform/agent-summaries', {}, { signal }),
  })
  const models = useComposerModels()
  const environments = useQuery({
    queryKey: queryKeys.environments.summaries,
    queryFn: () => request('GET /api/platform/environment-summaries', {}),
  })
  const agent = agents.data?.find(({ name, driver }) => name === space.defaultAgentName && driver != null)
  const modelSelection = model ?? (agent?.modelName ? { modelName: agent.modelName, variant: agent.variant } : null)
  const environment = environments.data?.find(({ id, name }) => environmentName == null
    ? id === space.defaultEnvironmentId : name === environmentName)
  const environmentLabel = space.defaultEnvironmentId ? t('ai.runtime.draft.environmentDefault') : undefined
  const yoloEnabled = yolo ?? space.defaultYoloEnabled

  const send = useMutation({
    mutationFn: async (record: SessionSendRecord) => {
      const { body, key } = record.value
      const result = await request('POST /api/chat-spaces/{spaceId}/sessions', {
        path: { spaceId: space.id }, body,
      }, { idempotencyKey: key })
      try {
        if (clearSessionSend(storageKey, record)) {
          // Another tab can edit this draft while our receipt is pending. Clear only the sent content.
          const persisted = partsToMessageContents(trimMessageParts(restoreComposerDraft(scope, [])))
          if (JSON.stringify(persisted) === JSON.stringify(body.message)) clearStoredComposerDraft(scope)
          return { ...result, cleared: true }
        }
      } catch {
        // 保留记录，清理失败时仍可使用同一个键重放。
      }
      return { ...result, cleared: false }
    },
    retry: false,
    networkMode: 'always',
    onError: (error, record) => {
      // 键冲突明确拒绝了当前请求体；鉴权失败和其他未知结果仍须原样重放。
      const conflicted = isConflictReason(error, 'IDEMPOTENCY_KEY_REUSED')
      // 非应用 400 也会得到客户端回退的 validation code，须有应用字段才允许纠正。
      const validationRejected = error instanceof ApiError && error.status === 400 && error.code === 'validation'
        && Boolean(error.reason?.trim() || error.detail?.trim())
      if (!conflicted && !validationRejected) return
      try {
        const { key, body } = record.value
        const rejected = saveSessionSend(storageKey, record,
          conflicted ? { key, body, rejected: true, conflicted: true } : { key, body, rejected: true })
        setStored({ record: rejected, unreadable: false })
      } catch {
        if (conflicted) setStored({ record, unreadable: true })
        setStorageError(t('ai.runtime.action.storageClearFailed'))
      }
    },
    onSettled: () => { sending.current = false },
    onSuccess: ({ threadId }) => {
      // 接受后读取服务端快照，不制造临时历史；首条命令在快照中是排队项或已物化的消息。
      void cache.prefetchQuery({
        queryKey: queryKeys.threads.snapshot(threadId),
        queryFn: ({ signal }) => fetchThreadSnapshot(cache, threadId, { signal }),
      })
      void cache.invalidateQueries({ queryKey: workspaceKeys.tree(space.id) })
    },
  })

  const locked = send.isPending || retryRecord != null
  function submit(body?: SessionCreateDTO) {
    if (sending.current || send.isPending || stored.unreadable) return
    let record: SessionSendRecord
    try {
      if (retryRecord) {
        if (loadSessionSend(storageKey)?.raw !== retryRecord.raw) {
          throw new Error('Session send record changed')
        }
        record = retryRecord
      } else {
        if (!body) return
        const key = stored.record?.value.conflicted ? createUuid() : stored.record?.value.key ?? target.draftId
        record = saveSessionSend(storageKey, stored.record, { key, body, rejected: false })
        setStored({ record, unreadable: false })
      }
    } catch {
      setStorageError(t('ai.runtime.action.storageFailed'))
      return
    }
    sending.current = true
    setStorageError(null)
    send.mutate(record, {
      onSuccess: ({ threadId, cleared }) => {
        if (!cleared) {
          setStorageError(t('ai.runtime.action.storageClearFailed'))
          return
        }
        setStored({ record: null, unreadable: false })
        clearParts()
        onUnsentChange(false)
        replace({ kind: 'thread', threadId })
      },
    })
  }

  const notice = retryRecord && !send.isPending ? <div className="thread-draft-notice" role="alert">
    {t('ai.runtime.draft.sendUnknown')}
    <Button variant="secondary" onClick={() => submit()}>{t('ai.interaction.retry')}</Button>
  </div> : agents.isError
    ? <div className="thread-draft-notice" role="alert">
      {apiErrorDetail(agents.error) ?? t('shared.requestFailed')}
      <Button variant="secondary" onClick={() => void agents.refetch()}>{t('ai.interaction.retry')}</Button>
    </div>
    : agents.data && !agent && <div className="thread-draft-notice" role="status">
      {t(agents.data.length ? 'ai.runtime.draft.defaultAgentMissing' : 'ai.chat.noAgents')}
      {agents.data.length > 0
        ? <Button variant="secondary" onClick={openSpaceSettings}>{t('ai.runtime.draft.spaceSettings')}</Button>
        : user?.role === 'ADMIN' && <Link className="btn-secondary" to="/settings/agents">{t('ai.runtime.draft.agentSettings')}</Link>}
    </div>
  const conflicted = stored.record?.value.conflicted || isConflictReason(send.error, 'IDEMPOTENCY_KEY_REUSED')
  const actionError = storageError ?? (stored.unreadable ? t('ai.runtime.action.storageFailed') : null) ?? (
    retryRecord || conflicted ? null : send.error instanceof ApiError && (send.error.reason === 'INVALID_FOLDER' || send.error.code === 'INVALID_FOLDER')
      ? t('ai.runtime.draft.invalidFolder')
      : send.error ? apiErrorDetail(send.error) ?? send.error.message : null
  )

  return <ThreadPanel
    transcript={{ messages: [], queuedMessages: [], loading: false, error: null, bodyRef }}
    activity={{ working: false, actionError, onDismissActionError: () => { setStorageError(null); if (!sending.current) send.reset() } }}
    controls={<RootThreadControlArea rootThreadId={null} messages={[]} queuedMessages={[]} notice={<>
      {conflicted && <div className="thread-draft-notice" role="alert">{t('ai.runtime.draft.sendConflict')}</div>}
      {notice}
    </>}
      composer={{
        parts,
        pending: send.isPending,
        disabled: locked,
        sendDisabled: stored.unreadable || !agent || agents.isError,
        focusOnEscape: active,
        scope,
        onPartsChange: changeParts,
        onHistoryPartsChange: changeParts,
        onSubmit: (payload: ComposerPart[]) => {
          if (locked) return
          submit({
            folderId: target.folderId,
            message: partsToMessageContents(payload),
            modelSelection,
            environmentId: environmentName == null ? undefined : environment?.id,
            yoloEnabled,
          })
        },
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
