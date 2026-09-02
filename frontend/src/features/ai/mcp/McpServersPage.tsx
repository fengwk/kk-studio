import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, RefreshCw, Server, Trash2, ShieldCheck, ShieldAlert } from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { NumberInput } from '@/shared/ui/console/NumberInput'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import type { McpServerDTO } from '@/shared/api/contracts/ai-mcp'
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

function formatIsoTime(value: string | number | null | undefined, locale: AppLocale): string {
  if (!value) return ''
  const parsed = typeof value === 'number' ? value : Date.parse(String(value))
  if (Number.isFinite(parsed)) {
    return formatDateTime24(new Date(parsed), locale)
  }
  return String(value)
}

interface CreateModalState {
  name: string
  url: string
  bearerToken: string
  timeoutMillis: string
  error: string | null
}

interface EditModalState {
  server: McpServerDTO
  url: string
  timeoutMillis: string
  tokenMode: 'keep' | 'clear' | 'set'
  bearerToken: string
  error: string | null
}

export function McpServersPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

  const [createModal, setCreateModal] = useState<CreateModalState | null>(null)
  const [editModal, setEditModal] = useState<EditModalState | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<McpServerDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const [refreshingId, setRefreshingId] = useState<string | null>(null)

  const serversQuery = useQuery({
    queryKey: queryKeys.mcpServers.list(1, 100),
    queryFn: () => mcpServerService.pageServers(1, 100),
  })

  const servers: McpServerDTO[] = serversQuery.data?.results ?? []

  const createMutation = useMutation({
    mutationFn: (data: { name: string; url: string; bearerToken?: string | null; timeoutMillis: number }) =>
      mcpServerService.createServer({
        name: data.name,
        url: data.url,
        bearerToken: data.bearerToken?.trim() ? data.bearerToken.trim() : null,
        timeoutMillis: data.timeoutMillis,
      }),
    onSuccess: () => {
      setCreateModal(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setCreateModal(null)
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      setCreateModal((prev) => (prev ? { ...prev, error: message } : null))
    },
  })

  const updateMutation = useMutation({
    mutationFn: (data: {
      id: string
      expectedVersion: string
      url: string
      timeoutMillis: number
      bearerToken?: string | null
    }) =>
      mcpServerService.updateServer(data.id, {
        expectedVersion: data.expectedVersion,
        url: data.url,
        timeoutMillis: data.timeoutMillis,
        bearerToken: data.bearerToken,
      }),
    onSuccess: () => {
      setEditModal(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
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

  const refreshMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) => {
      setRefreshingId(id)
      return mcpServerService.refreshServer(id, expectedVersion)
    },
    onSettled: () => {
      setRefreshingId(null)
    },
    onSuccess: () => {
      setActionError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setActionError(null)
        return
      }
      setActionError(err instanceof Error ? err.message : String(err))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      mcpServerService.deleteServer(id, expectedVersion),
    onSuccess: () => {
      setDeleteTarget(null)
      setDeleteError(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
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
    if (!createModal) return
    const name = createModal.name.trim()
    const url = createModal.url.trim()
    if (!name) {
      setCreateModal({ ...createModal, error: t('ai.mcp.name') })
      return
    }
    if (!/^[a-z][a-z0-9_]*$/.test(name) || name.length > 32) {
      setCreateModal({
        ...createModal,
        error: 'Name must start with lowercase letter, contain only a-z, 0-9, _, and be <= 32 chars',
      })
      return
    }
    if (!url) {
      setCreateModal({ ...createModal, error: t('ai.mcp.url') })
      return
    }
    const timeoutNum = Number(createModal.timeoutMillis) || 30000
    createMutation.mutate({
      name,
      url,
      bearerToken: createModal.bearerToken,
      timeoutMillis: timeoutNum,
    })
  }

  function handleUpdateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!editModal) return
    const url = editModal.url.trim()
    if (!url) {
      setEditModal({ ...editModal, error: t('ai.mcp.url') })
      return
    }
    let bearerToken: string | null | undefined
    if (editModal.tokenMode === 'keep') {
      bearerToken = undefined
    } else if (editModal.tokenMode === 'clear') {
      bearerToken = ''
    } else {
      bearerToken = editModal.bearerToken.trim()
    }

    const timeoutNum = Number(editModal.timeoutMillis) || 30000
    updateMutation.mutate({
      id: editModal.server.id,
      expectedVersion: editModal.server.version,
      url,
      timeoutMillis: timeoutNum,
      bearerToken,
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
              setActionError(null)
              setConflict(null)
              setCreateModal({
                name: '',
                url: '',
                bearerToken: '',
                timeoutMillis: '30000',
                error: null,
              })
            }}
          >
            <Plus aria-hidden="true" />
            {t('ai.mcp.create')}
          </button>
        </div>
      </nav>
      <div className="screen-body">
        {actionError && (
          <div className="form-error-banner" role="alert" style={{ marginBottom: 16 }}>
            <span>{actionError}</span>
            <button
              type="button"
              className="ghost-btn"
              onClick={() => setActionError(null)}
              aria-label={t('shared.close')}
            >
              {t('shared.close')}
            </button>
          </div>
        )}
        {serversQuery.isLoading && <StateBlock title={t('ai.mcp.loading')} />}
        {serversQuery.error && (
          <StateBlock
            title={
              serversQuery.error instanceof Error
                ? serversQuery.error.message
                : t('ai.mcp.loadFailed')
            }
            tone="danger"
          />
        )}
        {!serversQuery.isLoading && !serversQuery.error && (
          <div className="cards-grid">
            {servers.length === 0 ? (
              <StateBlock title={t('ai.mcp.empty')} />
            ) : (
              servers.map((server: McpServerDTO) => {
                const updateTime = formatIsoTime(server.updateTime, locale)
                return (
                  <article key={server.id} className="info-card">
                    <div className="head">
                      <div className="head-content">
                        <div className="icon-box">
                          <Server aria-hidden="true" />
                        </div>
                        <div className="text-content">
                          <h3 title={server.name}>{server.name}</h3>
                          <p title={server.url}>{server.url}</p>
                        </div>
                        <span
                          className={`status-pill${server.bearerTokenConfigured ? ' is-ready' : ' is-offline'}`}
                          title={
                            server.bearerTokenConfigured
                              ? t('ai.mcp.configured')
                              : t('ai.mcp.anonymous')
                          }
                        >
                          {server.bearerTokenConfigured ? (
                            <ShieldCheck size={12} style={{ marginRight: 4 }} aria-hidden="true" />
                          ) : (
                            <ShieldAlert size={12} style={{ marginRight: 4 }} aria-hidden="true" />
                          )}
                          {server.bearerTokenConfigured
                            ? t('ai.mcp.configured')
                            : t('ai.mcp.anonymous')}
                        </span>
                      </div>
                    </div>
                    <div className="meta-block">
                      <div className="meta-row">
                        <span className="lbl">{t('ai.mcp.timeout')}</span>
                        <span className="val">{server.timeoutMillis} ms</span>
                      </div>
                      <div className="meta-row">
                        <span className="lbl">{t('ai.mcp.version')}</span>
                        <span className="val">{server.version}</span>
                      </div>
                      {updateTime ? (
                        <div className="meta-row">
                          <span className="lbl">{t('ai.mcp.updated')}</span>
                          <span className="val">{updateTime}</span>
                        </div>
                      ) : null}
                    </div>
                    <div className="chat-card-foot split">
                      <button
                        type="button"
                        className="action-enter-btn"
                        onClick={() => {
                          setActionError(null)
                          setConflict(null)
                          refreshMutation.mutate({
                            id: server.id,
                            expectedVersion: server.version,
                          })
                        }}
                        disabled={refreshingId === server.id}
                      >
                        <RefreshCw
                          className={refreshingId === server.id ? 'spin' : ''}
                          aria-hidden="true"
                        />
                        {t('ai.mcp.refresh')}
                      </button>
                      <button
                        type="button"
                        className="action-enter-btn"
                        onClick={() => {
                          setActionError(null)
                          setConflict(null)
                          setEditModal({
                            server,
                            url: server.url,
                            timeoutMillis: String(server.timeoutMillis ?? '30000'),
                            tokenMode: 'keep',
                            bearerToken: '',
                            error: null,
                          })
                        }}
                      >
                        <Pencil aria-hidden="true" />
                        {t('ai.mcp.edit')}
                      </button>
                      <button
                        type="button"
                        className="action-enter-btn danger"
                        onClick={() => {
                          setActionError(null)
                          setConflict(null)
                          setDeleteError(null)
                          setDeleteTarget(server)
                        }}
                      >
                        <Trash2 aria-hidden="true" />
                        {t('ai.mcp.delete')}
                      </button>
                    </div>
                  </article>
                )
              })
            )}
          </div>
        )}
      </div>

      {createModal && (
        <ModalBackdrop onClose={() => setCreateModal(null)}>
          <div
            className="modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.mcp.create')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={t('ai.mcp.create')}
              onClose={() => setCreateModal(null)}
              closeDisabled={createMutation.isPending}
            />
            <form onSubmit={handleCreateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.name')}</FieldLabel>
                  <input
                    value={createModal.name}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, name: e.target.value, error: null })
                    }
                    placeholder="e.g. filesystem"
                    maxLength={32}
                    required
                    autoFocus
                  />
                  <span className="inline-hint">
                    Lowercase letters, numbers, and underscores (^[a-z][a-z0-9_]*$)
                  </span>
                </label>
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                  <input
                    type="url"
                    value={createModal.url}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, url: e.target.value, error: null })
                    }
                    placeholder="http://localhost:8000/mcp"
                    maxLength={2048}
                    required
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.mcp.bearerToken')}</FieldLabel>
                  <input
                    type="password"
                    value={createModal.bearerToken}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, bearerToken: e.target.value })
                    }
                    placeholder="Optional token"
                    autoComplete="off"
                  />
                </label>
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.timeout')}</FieldLabel>
                  <NumberInput
                    value={createModal.timeoutMillis}
                    onChange={(val) =>
                      setCreateModal({ ...createModal, timeoutMillis: val })
                    }
                    min={1}
                    step={1000}
                  />
                </label>
                {createModal.error && <p className="field-error">{createModal.error}</p>}
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => setCreateModal(null)}
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
            aria-label={t('ai.mcp.edit')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={`${t('ai.mcp.edit')} - ${editModal.server.name}`}
              onClose={() => setEditModal(null)}
              closeDisabled={updateMutation.isPending}
            />
            <form onSubmit={handleUpdateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                  <input
                    type="url"
                    value={editModal.url}
                    onChange={(e) =>
                      setEditModal({ ...editModal, url: e.target.value, error: null })
                    }
                    placeholder="http://localhost:8000/mcp"
                    maxLength={2048}
                    required
                    autoFocus
                  />
                </label>
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.timeout')}</FieldLabel>
                  <NumberInput
                    value={editModal.timeoutMillis}
                    onChange={(val) =>
                      setEditModal({ ...editModal, timeoutMillis: val })
                    }
                    min={1}
                    step={1000}
                  />
                </label>

                <fieldset className="form-group">
                  <legend>{t('ai.mcp.tokenMode')}</legend>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <input
                        type="radio"
                        name="tokenMode"
                        value="keep"
                        checked={editModal.tokenMode === 'keep'}
                        onChange={() => setEditModal({ ...editModal, tokenMode: 'keep' })}
                      />
                      <span>{t('ai.mcp.tokenKeep')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <input
                        type="radio"
                        name="tokenMode"
                        value="clear"
                        checked={editModal.tokenMode === 'clear'}
                        onChange={() => setEditModal({ ...editModal, tokenMode: 'clear' })}
                      />
                      <span>{t('ai.mcp.tokenClear')}</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <input
                        type="radio"
                        name="tokenMode"
                        value="set"
                        checked={editModal.tokenMode === 'set'}
                        onChange={() => setEditModal({ ...editModal, tokenMode: 'set' })}
                      />
                      <span>{t('ai.mcp.tokenSet')}</span>
                    </label>
                  </div>
                </fieldset>

                {editModal.tokenMode === 'set' && (
                  <label className="form-group">
                    <FieldLabel required>{t('ai.mcp.bearerToken')}</FieldLabel>
                    <input
                      type="password"
                      value={editModal.bearerToken}
                      onChange={(e) =>
                        setEditModal({ ...editModal, bearerToken: e.target.value })
                      }
                      placeholder="New token"
                      autoComplete="off"
                      required
                    />
                  </label>
                )}

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

      {deleteTarget && (
        <ConfirmActionModal
          modal={{
            title: t('ai.mcp.delete'),
            description: t('ai.mcp.deleteConfirm', { name: deleteTarget.name }),
            confirmLabel: t('ai.mcp.delete'),
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

      <ConflictPresenter
        conflict={conflict}
        onRefresh={() => {
          setConflict(null)
          setCreateModal(null)
          setEditModal(null)
          setDeleteTarget(null)
          setDeleteError(null)
          setActionError(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
          void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
