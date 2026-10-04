import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, Download, KeyRound, SlidersHorizontal, Trash2 } from 'lucide-react'
import {
  environmentEventLevelClass,
  filterEnvironments,
  formatTimestamp,
} from '@/features/ai/environment/environment-utils'
import { EnvironmentInstallModal } from '@/features/ai/environment/EnvironmentInstallModal'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import { CreateCard, StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { environmentService } from '@/shared/api/environment-service'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

function TagRow({ label, names, limit = 3 }: { label: string; names: string[]; limit?: number }) {
  const clean = names.map((name) => name.trim()).filter(Boolean)
  const visible = clean.slice(0, limit)
  const rest = clean.length - visible.length

  return (
    <div className="meta-row env-tag-row">
      <span className="lbl">{label}</span>
      {clean.length === 0 ? (
        <span className="val val-empty" />
      ) : (
        <div className="meta-chips meta-chips-single" title={clean.join(', ')}>
          {visible.map((name) => (
            <span key={name} className="meta-chip">
              {name}
            </span>
          ))}
          {rest > 0 ? <span className="meta-chip is-more">+{rest}</span> : null}
        </div>
      )}
    </div>
  )
}

export function EnvironmentsPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

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

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    refetchInterval: 10_000,
  })

  const environments = useMemo(
    () => filterEnvironments(environmentsQuery.data ?? [], ''),
    [environmentsQuery.data],
  )

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
        <div className="cards-grid environment-list">
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
            return (
              <article key={environment.id} className="info-card environment-card">
                <div className="head">
                  <div className="head-content">
                    <div className="icon-box">
                      <Box aria-hidden="true" />
                    </div>
                    <div className="text-content">
                      <h3 title={environment.name}>{environment.name}</h3>
                      <p title={environment.id}>
                        <code>{environment.id}</code>
                      </p>
                      {lastSeen ? (
                        <p title={lastSeen}>
                          {`${t('ai.environment.lastSeen')} · ${lastSeen}`}
                        </p>
                      ) : null}
                    </div>
                  </div>
                  <span className={`status-pill${ready ? ' is-ready' : ' is-offline'}`}>
                    {displayStatus}
                  </span>
                </div>
                <div className="meta-block">
                  {environment.userName ? (
                    <div className="meta-row">
                      <span className="lbl">{t('ai.environment.userName')}</span>
                      <span className="val" title={environment.userName}>
                        {environment.userName}
                      </span>
                    </div>
                  ) : null}
                  <TagRow label={t('ai.environment.capabilities')} names={capabilityIds} />
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
                </div>
                <div className="chat-card-foot split">
                  <button
                    type="button"
                    className="action-enter-btn"
                    aria-label={`${t('ai.environment.install')} ${environment.name}`}
                    onClick={() => setInstallTarget(environment)}
                  >
                    <Download aria-hidden="true" />
                    {t('ai.environment.install')}
                  </button>
                  <button type="button" className="action-enter-btn"
                    aria-label={`${t('ai.environment.uninstall')} ${environment.name}`}
                    onClick={() => setUninstallTarget(environment)}>
                    {t('ai.environment.uninstall')}
                  </button>
                  <button
                    type="button"
                    className="action-enter-btn"
                    aria-label={`${t('ai.environment.manage')} ${environment.name}`}
                    onClick={() => {
                      setConflict(null)
                      setManageTarget(environment)
                    }}
                  >
                    <SlidersHorizontal aria-hidden="true" />
                    {t('ai.environment.manage')}
                  </button>
                  <button
                    type="button"
                    className="action-enter-btn"
                    aria-label={`${t('ai.environment.rotateToken')} ${environment.name}`}
                    onClick={() => {
                      setConflict(null)
                      setRotateError(null)
                      setRotateTarget(environment)
                    }}
                  >
                    <KeyRound aria-hidden="true" />
                    {t('ai.environment.rotateToken')}
                  </button>
                  <button
                    type="button"
                    className="action-enter-btn danger"
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
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      </div>

      {createModalOpen && (
        <ModalBackdrop onClose={() => setCreateModalOpen(false)}>
          <div
            className="modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.environment.create')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={t('ai.environment.create')}
              onClose={() => setCreateModalOpen(false)}
              closeDisabled={createMutation.isPending}
            />
            <form onSubmit={handleCreateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.environment.name')}</FieldLabel>
                  <input
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
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => setCreateModalOpen(false)}
                  disabled={createMutation.isPending}
                >
                  {t('shared.cancel')}
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={createMutation.isPending}
                >
                  {t('shared.confirm')}
                </button>
              </div>
            </form>
          </div>
        </ModalBackdrop>
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
