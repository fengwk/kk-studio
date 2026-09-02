import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, Pencil, Plus, Trash2, Copy, Check } from 'lucide-react'
import { filterEnvironments } from '@/features/ai/environment/environment-utils'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { environmentService } from '@/shared/api/environment-service'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type AppLocale } from '@/shared/i18n'

function formatDateTime24(date: Date, locale: AppLocale): string {
  return date.toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatLastSeen(value: InstantTimestamp | undefined, locale: AppLocale): string {
  if (value == null || value === '') {
    return ''
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const ms = value < 1e12 ? value * 1000 : value
    return formatDateTime24(new Date(ms), locale)
  }
  const raw = String(value).trim()
  if (!raw) {
    return ''
  }
  if (/^\d+(\.\d+)?$/.test(raw)) {
    const n = Number(raw)
    if (Number.isFinite(n)) {
      const ms = n < 1e12 ? n * 1000 : n
      return formatDateTime24(new Date(ms), locale)
    }
  }
  const parsed = Date.parse(raw)
  if (Number.isFinite(parsed)) {
    return formatDateTime24(new Date(parsed), locale)
  }
  return raw
}

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

interface EditModalState {
  environment: EnvironmentCardDTO
  name: string
  error: string | null
}

interface TokenModalState {
  environmentName: string
  token: string
}

export function EnvironmentsPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [createName, setCreateName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)

  const [editModal, setEditModal] = useState<EditModalState | null>(null)
  const [tokenModal, setTokenModal] = useState<TokenModalState | null>(null)
  const [copied, setCopied] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<EnvironmentCardDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [rotateTarget, setRotateTarget] = useState<EnvironmentCardDTO | null>(null)
  const [rotateError, setRotateError] = useState<string | null>(null)
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
    mutationFn: (name: string) => environmentService.createEnvironment({ name }),
    onSuccess: (card) => {
      setCreateModalOpen(false)
      setCreateName('')
      setCreateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
      if (card.registrationToken) {
        setTokenModal({
          environmentName: card.name,
          token: card.registrationToken,
        })
      }
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

  const updateMutation = useMutation({
    mutationFn: ({
      id,
      expectedVersion,
      name,
    }: {
      id: string
      expectedVersion: string
      name: string
    }) => environmentService.updateEnvironment(id, expectedVersion, { name }),
    onSuccess: () => {
      setEditModal(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setEditModal(null)
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      setEditModal((prev) => (prev ? { ...prev, error: message } : null))
    },
  })

  const rotateMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      environmentService.rotateToken(id, expectedVersion),
    onSuccess: (card) => {
      setRotateTarget(null)
      setRotateError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
      if (card.registrationToken) {
        setTokenModal({
          environmentName: card.name,
          token: card.registrationToken,
        })
      }
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

  function handleUpdateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!editModal) return
    const trimmed = editModal.name.trim()
    if (!trimmed) {
      setEditModal({ ...editModal, error: t('ai.environment.name') })
      return
    }
    updateMutation.mutate({
      id: editModal.environment.id,
      expectedVersion: editModal.environment.version,
      name: trimmed,
    })
  }

  function handleCopyToken() {
    if (!tokenModal?.token) return
    navigator.clipboard.writeText(tokenModal.token).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
        <div className="subbar-actions">
          <button
            type="button"
            className="btn-primary"
            onClick={() => {
              setCreateName('')
              setCreateError(null)
              setCreateModalOpen(true)
            }}
          >
            <Plus aria-hidden="true" />
            {t('ai.environment.create')}
          </button>
        </div>
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
        {!environmentsQuery.isLoading && !environmentsQuery.error && (
          <div className="cards-grid environment-list">
            {environments.length === 0 ? (
              <StateBlock title={t('ai.environment.empty')} />
            ) : (
              environments.map((environment) => {
                const status = String(environment.status).toUpperCase()
                const ready = environment.ready === true
                const displayStatus = status === 'READY' && !ready ? 'UNAVAILABLE' : status
                const capabilityIds = (environment.capabilities ?? [])
                  .map((capability) => capability.id)
                  .filter(Boolean)
                const skillNames = (environment.skills ?? [])
                  .map((skill) => skill.name)
                  .filter(Boolean)
                const lastSeen = formatLastSeen(environment.lastSeen, locale)
                return (
                  <article key={environment.id} className="info-card environment-card">
                    <div className="head">
                      <div className="head-content">
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
                        <span className={`status-pill${ready ? ' is-ready' : ' is-offline'}`}>
                          {displayStatus}
                        </span>
                      </div>
                    </div>
                    <div className="meta-block">
                      {environment.rootPath ? (
                        <div className="meta-row">
                          <span className="lbl">{t('ai.environment.rootPath')}</span>
                          <span className="val" title={environment.rootPath}>
                            {environment.rootPath}
                          </span>
                        </div>
                      ) : null}
                      <TagRow label={t('ai.environment.capabilities')} names={capabilityIds} />
                      <TagRow label={t('ai.environment.skills')} names={skillNames} />
                    </div>
                    <div className="chat-card-foot split">
                      <button
                        type="button"
                        className="action-enter-btn"
                        onClick={() => {
                          setConflict(null)
                          setEditModal({
                            environment,
                            name: environment.name,
                            error: null,
                          })
                        }}
                      >
                        <Pencil aria-hidden="true" />
                        {t('ai.environment.edit')}
                      </button>
                      <button
                        type="button"
                        className="action-enter-btn"
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
                        onClick={() => {
                          setConflict(null)
                          setDeleteError(null)
                          setDeleteTarget(environment)
                        }}
                      >
                        <Trash2 aria-hidden="true" />
                        {t('ai.environment.delete')}
                      </button>
                    </div>
                  </article>
                )
              })
            )}
          </div>
        )}
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

      {editModal && (
        <ModalBackdrop onClose={() => setEditModal(null)}>
          <div
            className="modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.environment.edit')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={t('ai.environment.edit')}
              onClose={() => setEditModal(null)}
              closeDisabled={updateMutation.isPending}
            />
            <form onSubmit={handleUpdateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.environment.name')}</FieldLabel>
                  <input
                    value={editModal.name}
                    onChange={(e) =>
                      setEditModal({ ...editModal, name: e.target.value, error: null })
                    }
                    placeholder={t('ai.environment.namePlaceholder')}
                    maxLength={64}
                    required
                    autoFocus
                  />
                </label>
                {editModal.error && <p className="field-error">{editModal.error}</p>}
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => setEditModal(null)}
                  disabled={updateMutation.isPending}
                >
                  {t('shared.cancel')}
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={updateMutation.isPending}
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

      {tokenModal && (
        <ModalBackdrop onClose={() => setTokenModal(null)}>
          <div
            className="modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.environment.tokenTitle')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={`${t('ai.environment.tokenTitle')} - ${tokenModal.environmentName}`}
              onClose={() => setTokenModal(null)}
            />
            <div className="modal-body">
              <p className="confirm-modal-description" style={{ marginBottom: 16 }}>
                {t('ai.environment.tokenNotice')}
              </p>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  background: 'var(--surface)',
                  padding: '8px 12px',
                  borderRadius: 'var(--radius-md)',
                  border: '1px solid var(--border)',
                }}
              >
                <code
                  style={{
                    flex: 1,
                    wordBreak: 'break-all',
                    fontFamily: 'monospace',
                    fontSize: 13,
                    userSelect: 'all',
                  }}
                >
                  {tokenModal.token}
                </code>
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={handleCopyToken}
                  aria-label={t('ai.environment.copyToken')}
                  style={{ flexShrink: 0, display: 'inline-flex', alignItems: 'center', gap: 4 }}
                >
                  {copied ? <Check size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />}
                  {copied ? t('ai.environment.tokenCopied') : t('ai.environment.copyToken')}
                </button>
              </div>
            </div>
            <div className="modal-footer">
              <button
                type="button"
                className="btn-primary"
                onClick={() => setTokenModal(null)}
              >
                {t('ai.environment.close')}
              </button>
            </div>
          </div>
        </ModalBackdrop>
      )}

      <ConflictPresenter
        conflict={conflict}
        onRefresh={() => {
          setConflict(null)
          setCreateModalOpen(false)
          setCreateError(null)
          setEditModal(null)
          setRotateTarget(null)
          setRotateError(null)
          setDeleteTarget(null)
          setDeleteError(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
