import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, Check, Copy, KeyRound, SlidersHorizontal, Trash2 } from 'lucide-react'
import { filterEnvironments, formatTimestamp } from '@/features/ai/environment/environment-utils'
import { copyTextToClipboard } from '@/features/ai/environment/clipboard'
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
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
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

  const [tokenModal, setTokenModal] = useState<TokenModalState | null>(null)
  const [copied, setCopied] = useState(false)
  const [copiedEnvironmentId, setCopiedEnvironmentId] = useState<string | null>(null)
  const [copyError, setCopyError] = useState<string | null>(null)
  const copiedResetTimerRef = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (copiedResetTimerRef.current !== null) {
        window.clearTimeout(copiedResetTimerRef.current)
      }
    },
    [],
  )

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
        finishCopyFeedback(false)
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

  /**
   * 「复制 Token」：用户点击时才读取当前 token，读取成功即写入剪贴板。
   *
   * 读取是幂等只读动作（不轮换、不断开连接），且不写入任何持久化存储。
   */
  const copyTokenMutation = useMutation({
    mutationFn: (environment: EnvironmentCardDTO) =>
      environmentService.getRegistrationToken(environment.id).then((token) => ({
        environment,
        token,
      })),
    onSuccess: async ({ environment, token }) => {
      setCopyError(null)
      const ok = await copyTextToClipboard(token.registrationToken)
      if (!ok) {
        setCopyError(t('ai.environment.copyTokenFailed'))
        return
      }
      finishCopyFeedback(true, environment.id)
    },
    onError: (err: unknown) => {
      setCopyError(err instanceof Error ? err.message : String(err))
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

  /** 复制反馈：成功时标记 2 秒后自动复位，并清理上一个计时器。 */
  function finishCopyFeedback(success: boolean, environmentId?: string) {
    setCopied(success)
    setCopiedEnvironmentId(success ? (environmentId ?? null) : null)
    if (copiedResetTimerRef.current !== null) {
      window.clearTimeout(copiedResetTimerRef.current)
      copiedResetTimerRef.current = null
    }
    if (success) {
      copiedResetTimerRef.current = window.setTimeout(() => {
        copiedResetTimerRef.current = null
        setCopied(false)
        setCopiedEnvironmentId(null)
      }, 2000)
    }
  }

  /** 弹窗内复制：token 已在内存中，直接写剪贴板，不再发起请求。 */
  async function handleCopyToken() {
    if (!tokenModal?.token) return
    const ok = await copyTextToClipboard(tokenModal.token)
    if (ok) {
      finishCopyFeedback(true)
    }
  }

  /** 按需复制当前 token：读取请求只在点击时发出，绝不预取。 */
  function handleRequestCopyToken(environment: EnvironmentCardDTO) {
    setCopyError(null)
    copyTokenMutation.mutate(environment)
  }

  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
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
                </div>
                <div className="chat-card-foot split">
                  <button
                    type="button"
                    className="action-enter-btn"
                    aria-label={`${t('ai.environment.copyToken')} ${environment.name}`}
                    onClick={() => handleRequestCopyToken(environment)}
                    disabled={
                      copyTokenMutation.isPending
                      && copyTokenMutation.variables?.id === environment.id
                    }
                  >
                    {copied && copiedEnvironmentId === environment.id ? (
                      <Check aria-hidden="true" />
                    ) : (
                      <Copy aria-hidden="true" />
                    )}
                    {copied && copiedEnvironmentId === environment.id
                      ? t('ai.environment.tokenCopied')
                      : t('ai.environment.copyToken')}
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
                {copyError && copyTokenMutation.variables?.id === environment.id ? (
                  <p className="field-error" role="alert">
                    {copyError}
                  </p>
                ) : null}
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
