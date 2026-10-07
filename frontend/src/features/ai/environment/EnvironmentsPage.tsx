import { useCallback, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, Download, KeyRound, SlidersHorizontal, Trash2 } from 'lucide-react'
import {
  environmentEventLevelClass,
  filterEnvironments,
  formatTimestamp,
  timestampMillis,
} from '@/features/ai/environment/environment-utils'
import { EnvironmentInstallModal } from '@/features/ai/environment/EnvironmentInstallModal'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { ResourceCard, type ResourceCardMetaRow } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { environmentService } from '@/shared/api/environment-service'
import { useApplicationEvents } from '@/shared/app-events'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/**
 * 状态截止点回读的容差：越过截止点一个很小的余量再回读，避免与服务端时钟在边界上竞争。
 *
 * 它是「一次回读」的容差而非轮询周期：每个截止点只排一次，截止点变化才会重排。
 */
const STATUS_EXPIRY_RECHECK_SLACK_MS = 250

/** 权威列表里最早的状态截止时间（毫秒）；没有任何可解析的截止时间时为 null。 */
function earliestStatusExpiresAt(cards: EnvironmentCardDTO[]): number | null {
  let earliest: number | null = null
  for (const card of cards) {
    const at = timestampMillis(card.statusExpiresAt)
    if (at == null) {
      continue
    }
    if (earliest == null || at < earliest) {
      earliest = at
    }
  }
  return earliest
}

export function EnvironmentsPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()

  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [createName, setCreateName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)

  const [installTarget, setInstallTarget] = useState<EnvironmentCardDTO | null>(null)
  const [uninstallTarget, setUninstallTarget] = useState<EnvironmentCardDTO | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<EnvironmentCardDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [rotateTarget, setRotateTarget] = useState<EnvironmentCardDTO | null>(null)
  const [rotateError, setRotateError] = useState<string | null>(null)
  const [manageTarget, setManageTarget] = useState<EnvironmentCardDTO | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  const invalidateEnvironments = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }, [queryClient])

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  // 环境事实（注册表与连接租约，含心跳续租、断线与注销）由服务端在提交后推送 changed：
  // 只提示回读权威列表，因此不再用固定轮询掩盖状态变化；subscribed（首订与重连重订阅）
  // 与 resync 同样回读，关闭断线窗口。
  useEffect(
    () =>
      applicationEvents.subscribe(
        { kind: 'environments' },
        {
          onSubscribed: invalidateEnvironments,
          onEvent: (name) => {
            if (name === 'changed') {
              invalidateEnvironments()
            }
          },
          onResync: invalidateEnvironments,
        },
      ),
    [applicationEvents, invalidateEnvironments],
  )

  const environments = useMemo(
    () => filterEnvironments(environmentsQuery.data ?? [], ''),
    [environmentsQuery.data],
  )

  // 连接失效是时间事实，不是写入事实：服务端已给出每个有效状态的截止时间，这里只按最早的截止点排一次回读，
  // 不轮询、也不维护客户端过期集合。心跳续租或任何 changed 推送更新权威数据后，这个定时器会被取消并按新截止点重排；
  // 回读得到 OFFLINE（statusExpiresAt 为 null）后自然停止。
  const statusExpiresAt = useMemo(
    () => earliestStatusExpiresAt(environmentsQuery.data ?? []),
    [environmentsQuery.data],
  )

  useEffect(() => {
    if (statusExpiresAt == null) {
      return
    }
    // 截止点只是状态成立的上界：稍微越过它再回读，避免与服务端时钟在边界上竞争。
    const delay = Math.max(statusExpiresAt - Date.now(), 0) + STATUS_EXPIRY_RECHECK_SLACK_MS
    const timer = setTimeout(invalidateEnvironments, delay)
    return () => clearTimeout(timer)
  }, [statusExpiresAt, invalidateEnvironments])

  const createMutation = useMutation({
    mutationFn: async (name: string) => {
      const card = await environmentService.createEnvironment({ name })
      return { ...card, registrationToken: null }
    },
    onSuccess: (card) => {
      setCreateModalOpen(false)
      setCreateName('')
      setCreateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
      setInstallTarget({ ...card, registrationToken: null })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setCreateModalOpen(false)
        setCreateError(null)
        return
      }
      setCreateError(err instanceof Error ? err.message : String(err))
    },
  })

  const rotateMutation = useMutation({
    mutationFn: async ({ id, expectedVersion }: { id: string; expectedVersion: string }) => {
      const card = await environmentService.rotateToken(id, expectedVersion)
      return { ...card, registrationToken: null }
    },
    onSuccess: () => {
      setRotateTarget(null)
      setRotateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setRotateTarget(null)
        setRotateError(null)
        return
      }
      setRotateError(err instanceof Error ? err.message : String(err))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      environmentService.deleteEnvironment(id, expectedVersion),
    onSuccess: () => {
      setDeleteTarget(null)
      setDeleteError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setDeleteTarget(null)
        setDeleteError(null)
        return
      }
      setDeleteError(err instanceof Error ? err.message : String(err))
    },
  })

  function handleCreateSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = createName.trim()
    if (!trimmed) {
      setCreateError(t('ai.environment.name'))
      return
    }
    setCreateError(null)
    createMutation.mutate(trimmed)
  }

  return (
    <section className="screen active">
      <nav className="subbar">
        <AiNavigation />
      </nav>
      <div className="screen-body">
        {environmentsQuery.isLoading && <StateBlock title={t('ai.environment.loading')} />}
        {environmentsQuery.error && (
          <StateBlock
            title={
              environmentsQuery.error instanceof Error
                ? environmentsQuery.error.message
                : t('ai.environment.loadFailed')
            }
            tone="danger"
          />
        )}
        <ResourceGrid>
          <CreateCard
            title={t('ai.environment.create')}
            subtitle={t('ai.environment.createDescription')}
            onClick={() => {
              setCreateName('')
              setCreateError(null)
              setCreateModalOpen(true)
            }}
          />
          {environments.map((environment) => {
            const status = String(environment.status).toUpperCase()
            const ready = environment.ready === true
            const displayStatus = status === 'READY' && !ready ? 'UNAVAILABLE' : status
            const capabilityIds = (environment.capabilities ?? [])
              .map((capability) => capability.id)
              .filter(Boolean)
            const lastSeen = formatTimestamp(environment.lastSeen, locale)
            const lastEvent = environment.lastEvent
            const lastEventTime = lastEvent ? formatTimestamp(lastEvent.time, locale) : ''

            const rows: ResourceCardMetaRow[] = []
            if (environment.userName) {
              rows.push([t('ai.environment.userName'), environment.userName])
            }
            // 能力清单始终占一行：为空时留空，结构不随数据有无而变。
            rows.push({ label: t('ai.environment.capabilities'), tags: capabilityIds })

            return (
              <ResourceCard
                key={environment.id}
                className="environment-card"
                icon={<Box aria-hidden="true" />}
                title={environment.name}
                subtitle={environment.id}
                badge={
                  <span className={`status-pill${ready ? ' is-ready' : ' is-offline'}`}>
                    {displayStatus}
                  </span>
                }
                meta={rows}
                actions={
                  <>
                    <Button
                      size="compact"
                      aria-label={`${t('ai.environment.install')} ${environment.name}`}
                      onClick={() => setInstallTarget(environment)}
                    >
                      <Download aria-hidden="true" />
                      {t('ai.environment.install')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="compact"
                      aria-label={`${t('ai.environment.uninstall')} ${environment.name}`}
                      onClick={() => setUninstallTarget(environment)}
                    >
                      {t('ai.environment.uninstall')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="compact"
                      aria-label={`${t('ai.environment.manage')} ${environment.name}`}
                      onClick={() => {
                        setConflict(null)
                        setManageTarget(environment)
                      }}
                    >
                      <SlidersHorizontal aria-hidden="true" />
                      {t('ai.environment.manage')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="compact"
                      aria-label={`${t('ai.environment.rotateToken')} ${environment.name}`}
                      onClick={() => {
                        setConflict(null)
                        setRotateError(null)
                        setRotateTarget(environment)
                      }}
                    >
                      <KeyRound aria-hidden="true" />
                      {t('ai.environment.rotateToken')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="compact"
                      danger
                      aria-label={`${t('ai.environment.delete')} ${environment.name}`}
                      onClick={() => {
                        setConflict(null)
                        setDeleteError(null)
                        setDeleteTarget(environment)
                      }}
                      disabled={deleteMutation.isPending && deleteTarget?.id === environment.id}
                    >
                      <Trash2 aria-hidden="true" />
                      {t('ai.catalog.action.delete')}
                    </Button>
                  </>
                }
              >
                {lastSeen ? (
                  <p className="inline-hint" title={lastSeen}>
                    {`${t('ai.environment.lastSeen')} · ${lastSeen}`}
                  </p>
                ) : null}
                {lastEvent ? (
                  <div className={`env-last-event ${environmentEventLevelClass(lastEvent.level)}`}>
                    <div className="env-last-event-header">
                      <span
                        className={`env-event-level ${environmentEventLevelClass(lastEvent.level)}`}
                      >
                        {lastEvent.level}
                      </span>
                      <span className="env-event-type">{lastEvent.type}</span>
                      {lastEventTime ? (
                        <span className="env-event-time">{lastEventTime}</span>
                      ) : null}
                    </div>
                    <p className="env-last-event-message" title={lastEvent.message}>
                      {lastEvent.message}
                    </p>
                  </div>
                ) : null}
              </ResourceCard>
            )
          })}
        </ResourceGrid>
      </div>

      {createModalOpen && (
        <Dialog
          title={t('ai.environment.create')}
          onClose={() => setCreateModalOpen(false)}
          pending={createMutation.isPending}
        >
            <form className="modal-card-form" onSubmit={handleCreateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.environment.name')}</FieldLabel>
                  <TextInput
                    value={createName}
                    onChange={(e) => setCreateName(e.target.value)}
                    placeholder={t('ai.environment.namePlaceholder')}
                    maxLength={64}
                    required
                    autoFocus
                  />
                </label>
                {createError && <p className="field-error">{createError}</p>}
              </div>
              <div className="modal-footer">
                <Button
                  variant="ghost"
                  onClick={() => setCreateModalOpen(false)}
                  disabled={createMutation.isPending}
                >
                  {t('shared.cancel')}
                </Button>
                <Button
                  type="submit"
                  disabled={createMutation.isPending}
                >
                  {t('shared.confirm')}
                </Button>
              </div>
            </form>
        </Dialog>
      )}

      {rotateTarget && (
        <ConfirmActionModal
          modal={{
            title: t('ai.environment.rotateToken'),
            description: t('ai.environment.rotateTokenConfirm', { name: rotateTarget.name }),
            confirmLabel: t('ai.environment.rotateToken'),
            icon: 'refresh',
            error: rotateError,
            onConfirm: () =>
              rotateMutation.mutate({
                id: rotateTarget.id,
                expectedVersion: rotateTarget.version,
              }),
          }}
          pending={rotateMutation.isPending}
          onClose={() => {
            setRotateTarget(null)
            setRotateError(null)
          }}
        />
      )}

      {deleteTarget && (
        <ConfirmActionModal
          modal={{
            title: t('ai.environment.delete'),
            description: t('ai.environment.deleteConfirm', { name: deleteTarget.name }),
            confirmLabel: t('ai.environment.delete'),
            icon: 'delete',
            tone: 'danger',
            error: deleteError,
            onConfirm: () =>
              deleteMutation.mutate({
                id: deleteTarget.id,
                expectedVersion: deleteTarget.version,
              }),
          }}
          pending={deleteMutation.isPending}
          onClose={() => {
            setDeleteTarget(null)
            setDeleteError(null)
          }}
        />
      )}

      {installTarget && <EnvironmentInstallModal environment={installTarget} onClose={() => setInstallTarget(null)} />}
      {uninstallTarget && <EnvironmentInstallModal environment={uninstallTarget} uninstall onClose={() => setUninstallTarget(null)} />}

      {manageTarget && (
        <EnvironmentManagementModal
          environment={manageTarget}
          onClose={() => setManageTarget(null)}
        />
      )}

      <ConflictPresenter
        conflict={conflict}
        onRefresh={() => {
          setConflict(null)
          setCreateModalOpen(false)
          setCreateError(null)
          setRotateTarget(null)
          setRotateError(null)
          setDeleteTarget(null)
          setDeleteError(null)
          setManageTarget(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
