import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { CreateCard, StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import type { McpServerDTO } from '@/shared/api/contracts/ai-mcp'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type AppLocale } from '@/shared/i18n'
import {
  DEFAULT_TIMEOUT_MILLIS,
  hasUnsavedChanges,
  parseAndValidateHeaders,
  validateMcpName,
  validateMcpUrl,
  validateTimeoutMillis,
} from './mcp-utils'

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

function toEpochMillis(value: unknown): number | null {
  if (value == null || value === '') {
    return null
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.abs(value) < 100_000_000_000 ? value * 1000 : value
  }
  const raw = String(value).trim()
  if (!raw) {
    return null
  }
  if (/^-?\d+(\.\d+)?$/.test(raw)) {
    const num = Number(raw)
    if (Number.isFinite(num)) {
      return Math.abs(num) < 100_000_000_000 ? num * 1000 : num
    }
  }
  const parsed = Date.parse(raw)
  return Number.isFinite(parsed) ? parsed : null
}

function formatIsoTime(value: InstantTimestamp | undefined, locale: AppLocale): string {
  const millis = toEpochMillis(value)
  if (millis == null) {
    return value ? String(value) : ''
  }
  return formatDateTime24(new Date(millis), locale)
}

interface CreateModalState {
  name: string
  url: string
  headersText: string
  enabled: boolean
  timeoutMillis: string | number
  error: string | null
}

interface EditModalState {
  server: McpServerDTO
  loading: boolean
  fetchError: string | null
  loadedVersion: string | null
  loadedConfig: {
    url: string
    headers: Record<string, string>
    enabled: boolean
    timeoutMillis: number
  } | null
  url: string
  headersText: string
  enabled: boolean
  timeoutMillis: string | number
  error: string | null
  discoverPending: boolean
}

export function McpServersPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

  const [createModal, setCreateModal] = useState<CreateModalState | null>(null)
  const [editModal, setEditModal] = useState<EditModalState | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<McpServerDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  // 单调递增请求代际 token：关闭或重新打开同一 server 时递增，防止迟到响应覆写
  const editRequestGenerationRef = useRef(0)

  useEffect(() => {
    const generationRef = editRequestGenerationRef
    return () => {
      generationRef.current++
    }
  }, [])

  const serversQuery = useQuery({
    queryKey: queryKeys.mcpServers.list(1, 100),
    queryFn: () => mcpServerService.pageServers(1, 100),
  })

  const servers: McpServerDTO[] = serversQuery.data?.results ?? []

  const createMutation = useMutation({
    mutationFn: (data: {
      name: string
      url: string
      headers: Record<string, string>
      enabled: boolean
      timeoutMillis: number
    }) =>
      mcpServerService.createServer({
        name: data.name,
        url: data.url,
        headers: data.headers,
        enabled: data.enabled,
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
    mutationFn: (args: {
      name: string
      data: {
        expectedVersion: string
        url: string
        headers: Record<string, string>
        enabled: boolean
        timeoutMillis: number
      }
    }) =>
      mcpServerService.updateServer(args.name, {
        expectedVersion: args.data.expectedVersion,
        url: args.data.url,
        headers: args.data.headers,
        enabled: args.data.enabled,
        timeoutMillis: args.data.timeoutMillis,
      }),
    onSuccess: () => {
      editRequestGenerationRef.current++
      setEditModal(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        editRequestGenerationRef.current++
        setEditModal(null)
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      setEditModal((prev) => (prev ? { ...prev, error: message } : null))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ name, expectedVersion }: { name: string; expectedVersion: string }) =>
      mcpServerService.deleteServer(name, expectedVersion),
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

  function handleOpenCreate() {
    setConflict(null)
    setCreateModal({
      name: '',
      url: '',
      headersText: '',
      enabled: true,
      timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      error: null,
    })
  }

  function handleOpenEdit(server: McpServerDTO) {
    setConflict(null)
    const generation = ++editRequestGenerationRef.current
    setEditModal({
      server,
      loading: true,
      fetchError: null,
      loadedVersion: null,
      loadedConfig: null,
      url: '',
      headersText: '',
      enabled: server.enabled,
      timeoutMillis: Number(server.timeoutMillis) || DEFAULT_TIMEOUT_MILLIS,
      error: null,
      discoverPending: false,
    })

    mcpServerService
      .getServerConfig(server.name)
      .then((config) => {
        if (editRequestGenerationRef.current !== generation) {
          return
        }
        const rawHeaders = config.headers ?? {}
        const headersText = Object.keys(rawHeaders).length > 0 ? JSON.stringify(rawHeaders, null, 2) : ''
        const enabled = config.enabled ?? server.enabled
        const timeoutMillis =
          Number(config.timeoutMillis ?? server.timeoutMillis) || DEFAULT_TIMEOUT_MILLIS
        const loadedConfig = {
          url: config.url ?? '',
          headers: rawHeaders,
          enabled,
          timeoutMillis,
        }
        setEditModal((prev) => {
          if (!prev || editRequestGenerationRef.current !== generation) return null
          return {
            ...prev,
            loading: false,
            loadedVersion: config.version,
            loadedConfig,
            url: config.url ?? '',
            headersText,
            enabled,
            timeoutMillis,
          }
        })
      })
      .catch((err) => {
        if (editRequestGenerationRef.current !== generation) {
          return
        }
        setEditModal((prev) => {
          if (!prev || editRequestGenerationRef.current !== generation) return null
          return {
            ...prev,
            loading: false,
            fetchError: err instanceof Error ? err.message : String(err),
          }
        })
      })
  }

  function handleCloseEdit() {
    editRequestGenerationRef.current++
    setEditModal(null)
  }

  function handleRequestCloseCreate() {
    if (!createMutation.isPending) {
      setCreateModal(null)
    }
  }

  function handleRequestCloseEdit() {
    if (!updateMutation.isPending && !editModal?.discoverPending) {
      handleCloseEdit()
    }
  }

  function handleCreateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!createModal) return

    const nameVal = validateMcpName(createModal.name)
    if (!nameVal.valid) {
      setCreateModal({ ...createModal, error: nameVal.error ?? 'Invalid name' })
      return
    }

    const urlVal = validateMcpUrl(createModal.url)
    if (!urlVal.valid) {
      setCreateModal({ ...createModal, error: urlVal.error ?? 'Invalid URL' })
      return
    }

    const headersVal = parseAndValidateHeaders(createModal.headersText)
    if (!headersVal.valid) {
      setCreateModal({ ...createModal, error: headersVal.error ?? 'Invalid headers' })
      return
    }

    const timeoutVal = validateTimeoutMillis(createModal.timeoutMillis)
    if (!timeoutVal.valid) {
      setCreateModal({ ...createModal, error: timeoutVal.error ?? 'Invalid timeout' })
      return
    }

    createMutation.mutate({
      name: createModal.name.trim(),
      url: createModal.url,
      headers: headersVal.headers,
      enabled: createModal.enabled,
      timeoutMillis: timeoutVal.timeoutMillis,
    })
  }

  function handleUpdateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!editModal || !editModal.loadedVersion) return

    const urlVal = validateMcpUrl(editModal.url)
    if (!urlVal.valid) {
      setEditModal({ ...editModal, error: urlVal.error ?? 'Invalid URL' })
      return
    }

    const headersVal = parseAndValidateHeaders(editModal.headersText)
    if (!headersVal.valid) {
      setEditModal({ ...editModal, error: headersVal.error ?? 'Invalid headers' })
      return
    }

    const timeoutVal = validateTimeoutMillis(editModal.timeoutMillis)
    if (!timeoutVal.valid) {
      setEditModal({ ...editModal, error: timeoutVal.error ?? 'Invalid timeout' })
      return
    }

    updateMutation.mutate({
      name: editModal.server.name,
      data: {
        expectedVersion: editModal.loadedVersion,
        url: editModal.url,
        headers: headersVal.headers,
        enabled: editModal.enabled,
        timeoutMillis: timeoutVal.timeoutMillis,
      },
    })
  }

  async function handleDiscover() {
    if (!editModal || !editModal.loadedVersion) return
    setConflict(null)

    const headersVal = parseAndValidateHeaders(editModal.headersText)
    if (!headersVal.valid) {
      setEditModal((prev) =>
        prev ? { ...prev, error: headersVal.error ?? 'Invalid headers' } : null,
      )
      return
    }

    const timeoutVal = validateTimeoutMillis(editModal.timeoutMillis)
    if (!timeoutVal.valid) {
      setEditModal((prev) =>
        prev ? { ...prev, error: timeoutVal.error ?? 'Invalid timeout' } : null,
      )
      return
    }

    if (editModal.loadedConfig) {
      const currentFields = {
        url: editModal.url,
        headers: headersVal.headers,
        enabled: editModal.enabled,
        timeoutMillis: timeoutVal.timeoutMillis,
      }
      if (hasUnsavedChanges(currentFields, editModal.loadedConfig)) {
        setEditModal((prev) =>
          prev ? { ...prev, error: t('ai.mcp.unsavedDiscoverBlocked') } : null,
        )
        return
      }
    }

    setEditModal((prev) => (prev ? { ...prev, discoverPending: true, error: null } : null))
    try {
      await mcpServerService.discoverServer(editModal.server.name, editModal.loadedVersion)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
      handleCloseEdit()
    } catch (err) {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        handleCloseEdit()
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      setEditModal((prev) => (prev ? { ...prev, discoverPending: false, error: message } : null))
    }
  }

  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
      </nav>

      <div className="screen-body">
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
        <div className="cards-grid">
          <CreateCard
            title={t('ai.mcp.create')}
            subtitle={t('ai.mcp.createDescription')}
            onClick={handleOpenCreate}
          />
          {servers.map((server: McpServerDTO) => {
            const updateTime = formatIsoTime(server.updateTime, locale)

            const statusLabel =
              server.discoveryStatus === 'AVAILABLE'
                ? t('ai.mcp.statusAvailable')
                : server.discoveryStatus === 'FAILED'
                  ? t('ai.mcp.statusFailed')
                  : server.discoveryStatus === 'UNVERIFIED'
                    ? t('ai.mcp.statusUnverified')
                    : String(server.discoveryStatus)

            const rows: Array<
              | [string, string]
              | { label: string; value: string; wrap?: boolean }
              | { pairs: Array<{ label: string; value: string }> }
            > = [
              {
                pairs: [
                  { label: t('ai.mcp.status'), value: statusLabel },
                  { label: t('ai.mcp.toolCount'), value: String(server.toolCount) },
                ],
              },
              {
                pairs: [
                  { label: t('ai.mcp.version'), value: String(server.version) },
                  { label: t('ai.catalog.card.timeout'), value: `${server.timeoutMillis}ms` },
                ],
              },
              {
                pairs: [
                  {
                    label: t('ai.mcp.enabledState'),
                    value: server.enabled ? t('ai.mcp.enabled') : t('ai.mcp.disabled'),
                  },
                ],
              },
              ...(updateTime ? [[t('ai.mcp.updated'), updateTime] as [string, string]] : []),
            ]

            return (
              <ResourceCardLayout
                key={server.name}
                icon="server"
                title={server.name}
                subtitle="Streamable HTTP"
                rows={rows}
                editAriaLabel={`${t('ai.mcp.edit')} ${server.name}`}
                deleteAriaLabel={`${t('ai.mcp.delete')} ${server.name}`}
                onEdit={() => handleOpenEdit(server)}
                onDelete={() => {
                  setConflict(null)
                  setDeleteError(null)
                  setDeleteTarget(server)
                }}
                deletePending={deleteMutation.isPending && deleteTarget?.name === server.name}
              />
            )
          })}
        </div>
      </div>

      {createModal && (
        <ModalBackdrop onClose={handleRequestCloseCreate}>
          <div
            className="modal-card mcp-modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.mcp.create')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={t('ai.mcp.create')}
              onClose={handleRequestCloseCreate}
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
                    disabled={createMutation.isPending}
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
                    value={createModal.url}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, url: e.target.value, error: null })
                    }
                    placeholder="https://example.com/mcp"
                    maxLength={2048}
                    disabled={createMutation.isPending}
                    required
                  />
                  <span className="inline-hint">
                    Streamable HTTP endpoint URL (http:// or https://)
                  </span>
                </label>

                <label className="form-group">
                  <FieldLabel>{t('ai.mcp.headers')}</FieldLabel>
                  <textarea
                    className="code-textarea mcp-headers-textarea"
                    value={createModal.headersText}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, headersText: e.target.value, error: null })
                    }
                    placeholder={'{\n  "Authorization": "Bearer ${AUTH_TOKEN}"\n}'}
                    disabled={createMutation.isPending}
                    rows={4}
                  />
                  <span className="inline-hint">{t('ai.mcp.headersHint')}</span>
                </label>

                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.card.timeout')}</FieldLabel>
                  <input
                    type="number"
                    min={1}
                    step={1}
                    value={createModal.timeoutMillis}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, timeoutMillis: e.target.value, error: null })
                    }
                    placeholder="60000"
                    disabled={createMutation.isPending}
                  />
                </label>

                <label className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={createModal.enabled}
                    onChange={(e) =>
                      setCreateModal({ ...createModal, enabled: e.target.checked, error: null })
                    }
                    disabled={createMutation.isPending}
                  />
                  <span>{t('ai.mcp.enabled')}</span>
                </label>

                {createModal.error && (
                  <p className="field-error" role="alert">
                    {createModal.error}
                  </p>
                )}
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={handleRequestCloseCreate}
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
        <ModalBackdrop onClose={handleRequestCloseEdit}>
          <div
            className="modal-card mcp-modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={`${t('ai.mcp.edit')} · ${editModal.server.name}`}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={`${t('ai.mcp.edit')} · ${editModal.server.name}`}
              onClose={handleRequestCloseEdit}
              closeDisabled={updateMutation.isPending || editModal.discoverPending}
            />
            {editModal.loading ? (
              <div className="modal-body">
                <StateBlock title={t('ai.mcp.loadingConfig')} />
              </div>
            ) : editModal.fetchError ? (
              <div className="modal-body">
                <StateBlock
                  title={editModal.fetchError || t('ai.mcp.loadConfigFailed')}
                  tone="danger"
                />
                <div style={{ marginTop: 12, textAlign: 'center' }}>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => handleOpenEdit(editModal.server)}
                  >
                    {t('shared.retry')}
                  </button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleUpdateSubmit}>
                <div className="modal-body">
                  <label className="form-group">
                    <FieldLabel>{t('ai.mcp.name')}</FieldLabel>
                    <input
                      value={editModal.server.name}
                      disabled
                      readOnly
                    />
                  </label>

                  <label className="form-group">
                    <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                    <input
                      value={editModal.url}
                      onChange={(e) =>
                        setEditModal({ ...editModal, url: e.target.value, error: null })
                      }
                      placeholder="https://example.com/mcp"
                      maxLength={2048}
                      disabled={updateMutation.isPending || editModal.discoverPending}
                      required
                      autoFocus
                    />
                  </label>

                  <label className="form-group">
                    <FieldLabel>{t('ai.mcp.headers')}</FieldLabel>
                    <textarea
                      className="code-textarea mcp-headers-textarea"
                      value={editModal.headersText}
                      onChange={(e) =>
                        setEditModal({ ...editModal, headersText: e.target.value, error: null })
                      }
                      disabled={updateMutation.isPending || editModal.discoverPending}
                      rows={4}
                    />
                    <span className="inline-hint">{t('ai.mcp.headersHint')}</span>
                  </label>

                  <label className="form-group">
                    <FieldLabel>{t('ai.catalog.card.timeout')}</FieldLabel>
                    <input
                      type="number"
                      min={1}
                      step={1}
                      value={editModal.timeoutMillis}
                      onChange={(e) =>
                        setEditModal({ ...editModal, timeoutMillis: e.target.value, error: null })
                      }
                      disabled={updateMutation.isPending || editModal.discoverPending}
                    />
                  </label>

                  <label className="checkbox-field">
                    <input
                      type="checkbox"
                      checked={editModal.enabled}
                      onChange={(e) =>
                        setEditModal({ ...editModal, enabled: e.target.checked, error: null })
                      }
                      disabled={updateMutation.isPending || editModal.discoverPending}
                    />
                    <span>{t('ai.mcp.enabled')}</span>
                  </label>

                  {editModal.error && (
                    <p className="field-error" role="alert">
                      {editModal.error}
                    </p>
                  )}
                </div>

                <div className="modal-footer modal-footer-with-leading-action">
                  <button
                    type="button"
                    className="ghost-btn modal-footer-leading-action"
                    onClick={handleDiscover}
                    disabled={editModal.discoverPending || updateMutation.isPending}
                  >
                    <RefreshCw
                      className={editModal.discoverPending ? 'spin' : ''}
                      aria-hidden="true"
                    />
                    {t('ai.mcp.discover')}
                  </button>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={handleRequestCloseEdit}
                    disabled={updateMutation.isPending || editModal.discoverPending}
                  >
                    {t('shared.cancel')}
                  </button>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={updateMutation.isPending || editModal.discoverPending}
                  >
                    {t('shared.confirm')}
                  </button>
                </div>
              </form>
            )}
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
                name: deleteTarget.name,
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
          handleCloseEdit()
          setDeleteTarget(null)
          setDeleteError(null)
          void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
          void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
        }}
        onClose={() => setConflict(null)}
      />
    </section>
  )
}
