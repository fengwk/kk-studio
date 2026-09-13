import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw } from 'lucide-react'
import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { mcpServerService } from '@/shared/api/mcp-server-service'
import { DEFAULT_OPERATION_LIMIT, environmentService } from '@/shared/api/environment-service'
import type { McpServerDTO } from '@/shared/api/contracts/ai-mcp'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n, type AppLocale } from '@/shared/i18n'
import {
  createLocalConfigTemplate,
  extractDraftConnectionType,
  extractDraftEnvironmentId,
  formatMcpConfigJson,
  formatMcpConfigJsonSafely,
  isSemanticConfigEqual,
  REMOTE_CONFIG_TEMPLATE,
  updateLocalEnvironmentIdInJson,
  validateMcpConfigJson,
} from './mcp-config-json'

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
  configJson: string
  validationMessage: string | null
  validationTone: 'success' | 'error' | null
  error: string | null
}

interface EditModalState {
  server: McpServerDTO
  loading: boolean
  fetchError: string | null
  loadedVersion: string | null
  loadedConfigJson: string | null
  configJson: string
  validationMessage: string | null
  validationTone: 'success' | 'error' | null
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

  // 围栏追踪当前正在编辑的 server ID，防止晚到达的异步配置响应覆写其他 server 或已关闭的弹窗
  const activeEditServerIdRef = useRef<string | null>(null)

  // 10s 轮询以支持 Local 发现任务的最终状态收敛
  const serversQuery = useQuery({
    queryKey: queryKeys.mcpServers.list(1, 100),
    queryFn: () => mcpServerService.pageServers(1, 100),
    refetchInterval: 10_000,
  })

  const environmentsQuery = useQuery({
    queryKey: ['environments', 'list'],
    queryFn: () => environmentService.listEnvironments(),
  })

  const servers: McpServerDTO[] = serversQuery.data?.results ?? []
  const environments: EnvironmentCardDTO[] = useMemo(
    () => environmentsQuery.data ?? [],
    [environmentsQuery.data],
  )

  const environmentsMap = useMemo(() => {
    const map = new Map<string, EnvironmentCardDTO>()
    for (const env of environments) {
      map.set(env.id, env)
    }
    return map
  }, [environments])

  const createMutation = useMutation({
    mutationFn: (data: { name: string; configJson: string }) =>
      mcpServerService.createServer({
        name: data.name,
        configJson: data.configJson,
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
    mutationFn: (data: { id: string; expectedVersion: string; configJson: string }) =>
      mcpServerService.updateServer(data.id, {
        expectedVersion: data.expectedVersion,
        configJson: data.configJson,
      }),
    onSuccess: () => {
      activeEditServerIdRef.current = null
      setEditModal(null)
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        activeEditServerIdRef.current = null
        setEditModal(null)
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      setEditModal((prev) => (prev ? { ...prev, error: message } : null))
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

  function handleOpenCreate() {
    setConflict(null)
    setCreateModal({
      name: '',
      configJson: REMOTE_CONFIG_TEMPLATE,
      validationMessage: null,
      validationTone: null,
      error: null,
    })
  }

  function handleOpenEdit(server: McpServerDTO) {
    setConflict(null)
    activeEditServerIdRef.current = server.id
    setEditModal({
      server,
      loading: true,
      fetchError: null,
      loadedVersion: null,
      loadedConfigJson: null,
      configJson: '',
      validationMessage: null,
      validationTone: null,
      error: null,
      discoverPending: false,
    })

    mcpServerService
      .getServerConfig(server.id)
      .then((config) => {
        // 异步围栏检查：如果当前弹窗已被关闭或切换到其他 server，忽略迟到的响应
        if (activeEditServerIdRef.current === server.id) {
          const pretty = formatMcpConfigJsonSafely(config.configJson)
          setEditModal((prev) => {
            if (!prev || prev.server.id !== server.id) return prev
            return {
              ...prev,
              loading: false,
              loadedVersion: config.version,
              loadedConfigJson: pretty,
              configJson: pretty,
            }
          })
        }
      })
      .catch((err) => {
        if (activeEditServerIdRef.current === server.id) {
          setEditModal((prev) => {
            if (!prev || prev.server.id !== server.id) return prev
            return {
              ...prev,
              loading: false,
              fetchError: err instanceof Error ? err.message : String(err),
            }
          })
        }
      })
  }

  function handleCloseEdit() {
    activeEditServerIdRef.current = null
    setEditModal(null)
  }

  function handleCreateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!createModal) return
    const name = createModal.name.trim()
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
    const validation = validateMcpConfigJson(createModal.configJson)
    if (!validation.valid) {
      setCreateModal({ ...createModal, error: validation.error ?? 'Invalid JSON' })
      return
    }
    createMutation.mutate({
      name,
      configJson: createModal.configJson,
    })
  }

  function handleUpdateSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!editModal || !editModal.loadedVersion) return

    const validation = validateMcpConfigJson(editModal.configJson)
    if (!validation.valid) {
      setEditModal({ ...editModal, error: validation.error ?? 'Invalid JSON' })
      return
    }

    updateMutation.mutate({
      id: editModal.server.id,
      expectedVersion: editModal.loadedVersion,
      configJson: editModal.configJson,
    })
  }

  async function handleDiscover() {
    if (!editModal || !editModal.loadedVersion) return
    setConflict(null)

    // 严禁发现未保存的语义修改：空白等格式差异不影响语义
    const semanticallyEqual = isSemanticConfigEqual(
      editModal.configJson,
      editModal.loadedConfigJson ?? '',
    )
    if (!semanticallyEqual) {
      setEditModal((prev) =>
        prev ? { ...prev, error: t('ai.mcp.unsavedDiscoverBlocked') } : null,
      )
      return
    }

    setEditModal((prev) => (prev ? { ...prev, discoverPending: true, error: null } : null))
    try {
      const res = await mcpServerService.discoverServer(
        editModal.server.id,
        editModal.loadedVersion,
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
      void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
      if (res.operation && res.operation.environmentId) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.environments.operations(
            res.operation.environmentId,
            DEFAULT_OPERATION_LIMIT,
          ),
        })
        void queryClient.invalidateQueries({
          queryKey: queryKeys.environments.detail(res.operation.environmentId),
        })
      }
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
        <div className="subbar-actions">
          <button type="button" className="btn-primary" onClick={handleOpenCreate}>
            <Plus aria-hidden="true" />
            {t('ai.mcp.create')}
          </button>
        </div>
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
        {!serversQuery.isLoading && !serversQuery.error && (
          <div className="cards-grid">
            {servers.length === 0 ? (
              <StateBlock title={t('ai.mcp.empty')} />
            ) : (
              servers.map((server: McpServerDTO) => {
                const updateTime = formatIsoTime(server.updateTime, locale)
                const envName = server.environmentId
                  ? environmentsMap.get(server.environmentId)?.name ?? server.environmentId
                  : null

                const typeLabel =
                  server.type === 'local'
                    ? t('ai.mcp.typeLocal')
                    : server.type === 'remote'
                      ? t('ai.mcp.typeRemote')
                      : String(server.type)

                const statusLabel =
                  server.discoveryStatus === 'AVAILABLE'
                    ? t('ai.mcp.statusAvailable')
                    : server.discoveryStatus === 'FAILED'
                      ? t('ai.mcp.statusFailed')
                      : server.discoveryStatus === 'UNVERIFIED'
                        ? t('ai.mcp.statusUnverified')
                        : String(server.discoveryStatus)

                const subtitle =
                  server.type === 'local'
                    ? envName
                      ? `Local · ${envName}`
                      : 'Local'
                    : 'Remote'

                const rows: Array<
                  | [string, string]
                  | { label: string; value: string; wrap?: boolean }
                  | { pairs: Array<{ label: string; value: string }> }
                > = [
                  {
                    pairs: [
                      { label: t('ai.mcp.type'), value: typeLabel },
                      { label: t('ai.mcp.status'), value: statusLabel },
                    ],
                  },
                  ...(server.type === 'local' && envName
                    ? [{ label: t('ai.mcp.envSelect'), value: envName, wrap: true }]
                    : []),
                  {
                    pairs: [
                      { label: t('ai.mcp.toolCount'), value: String(server.toolCount) },
                      { label: t('ai.catalog.card.timeout'), value: `${server.timeoutMillis}ms` },
                    ],
                  },
                  {
                    pairs: [
                      { label: t('ai.mcp.version'), value: String(server.version) },
                      {
                        label: t('ai.mcp.enabled'),
                        value: server.enabled ? t('ai.mcp.enabled') : t('ai.mcp.disabled'),
                      },
                    ],
                  },
                  ...(server.discoveredVersion
                    ? [
                        {
                          label: t('ai.mcp.discoveredVersion'),
                          value: String(server.discoveredVersion),
                        },
                      ]
                    : []),
                  ...(updateTime ? [[t('ai.mcp.updated'), updateTime] as [string, string]] : []),
                ]

                return (
                  <ResourceCardLayout
                    key={server.id}
                    icon="server"
                    title={server.name}
                    subtitle={subtitle}
                    rows={rows}
                    editAriaLabel={`${t('ai.mcp.edit')} ${server.name}`}
                    deleteAriaLabel={`${t('ai.mcp.delete')} ${server.name}`}
                    onEdit={() => handleOpenEdit(server)}
                    onDelete={() => {
                      setConflict(null)
                      setDeleteError(null)
                      setDeleteTarget(server)
                    }}
                    deletePending={deleteMutation.isPending && deleteTarget?.id === server.id}
                  />
                )
              })
            )}
          </div>
        )}
      </div>

      {createModal && (
        <ModalBackdrop onClose={() => setCreateModal(null)}>
          <div
            className="modal-card mcp-modal-card"
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

                <div className="form-group">
                  <FieldLabel required>{t('ai.mcp.configJson')}</FieldLabel>
                  <div className="mcp-json-toolbar">
                    <button
                      type="button"
                      className="ghost-btn btn-sm"
                      onClick={() => {
                        setCreateModal({
                          ...createModal,
                          configJson: REMOTE_CONFIG_TEMPLATE,
                          validationMessage: null,
                          validationTone: null,
                          error: null,
                        })
                      }}
                    >
                      {t('ai.mcp.remoteTemplate')}
                    </button>
                    <button
                      type="button"
                      className="ghost-btn btn-sm"
                      onClick={() => {
                        const firstEnvId = environments[0]?.id
                        setCreateModal({
                          ...createModal,
                          configJson: createLocalConfigTemplate(firstEnvId),
                          validationMessage: null,
                          validationTone: null,
                          error: null,
                        })
                      }}
                    >
                      {t('ai.mcp.localTemplate')}
                    </button>
                    <button
                      type="button"
                      className="ghost-btn btn-sm"
                      onClick={() => {
                        try {
                          const formatted = formatMcpConfigJson(createModal.configJson)
                          setCreateModal({
                            ...createModal,
                            configJson: formatted,
                            validationMessage: null,
                            validationTone: null,
                          })
                        } catch (err) {
                          setCreateModal({
                            ...createModal,
                            validationMessage: err instanceof Error ? err.message : String(err),
                            validationTone: 'error',
                          })
                        }
                      }}
                    >
                      {t('ai.mcp.formatJson')}
                    </button>
                    <button
                      type="button"
                      className="ghost-btn btn-sm"
                      onClick={() => {
                        const res = validateMcpConfigJson(createModal.configJson)
                        if (res.valid) {
                          setCreateModal({
                            ...createModal,
                            validationMessage: t('ai.mcp.validationPassed'),
                            validationTone: 'success',
                          })
                        } else {
                          setCreateModal({
                            ...createModal,
                            validationMessage: res.error ?? 'Invalid JSON',
                            validationTone: 'error',
                          })
                        }
                      }}
                    >
                      {t('ai.mcp.validateJson')}
                    </button>
                  </div>

                  {extractDraftConnectionType(createModal.configJson) === 'local' && (
                    <label className="form-group" style={{ marginBottom: 8 }}>
                      <FieldLabel>{t('ai.mcp.envSelect')}</FieldLabel>
                      <select
                        aria-label={t('ai.mcp.envSelect')}
                        value={extractDraftEnvironmentId(createModal.configJson) ?? ''}
                        onChange={(e) => {
                          const selected = e.target.value
                          if (selected) {
                            const rewritten = updateLocalEnvironmentIdInJson(
                              createModal.configJson,
                              selected,
                            )
                            setCreateModal({
                              ...createModal,
                              configJson: rewritten,
                              error: null,
                            })
                          }
                        }}
                      >
                        <option value="" disabled>
                          -- {t('ai.mcp.envSelect')} --
                        </option>
                        {environments.map((env) => (
                          <option key={env.id} value={env.id}>
                            {env.name} ({env.id.slice(0, 8)}...)
                          </option>
                        ))}
                      </select>
                    </label>
                  )}

                  <textarea
                    className="code-textarea mcp-config-json-textarea"
                    aria-label={t('ai.mcp.configJson')}
                    value={createModal.configJson}
                    onChange={(e) =>
                      setCreateModal({
                        ...createModal,
                        configJson: e.target.value,
                        validationMessage: null,
                        error: null,
                      })
                    }
                    placeholder="{}"
                    rows={12}
                    required
                  />

                  {createModal.validationMessage && (
                    <div
                      className={`mcp-validation-msg is-${createModal.validationTone ?? 'info'}`}
                      role={createModal.validationTone === 'error' ? 'alert' : 'status'}
                    >
                      {createModal.validationMessage}
                    </div>
                  )}
                </div>

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
                  onClick={() => setCreateModal(null)}
                  disabled={createMutation.isPending}
                >
                  {t('shared.cancel')}
                </button>
                <button type="submit" className="btn-primary" disabled={createMutation.isPending}>
                  {t('shared.confirm')}
                </button>
              </div>
            </form>
          </div>
        </ModalBackdrop>
      )}

      {editModal && (
        <ModalBackdrop
          onClose={() => {
            if (!updateMutation.isPending && !editModal.discoverPending) {
              handleCloseEdit()
            }
          }}
        >
          <div
            className="modal-card mcp-modal-card"
            role="dialog"
            aria-modal="true"
            aria-label={t('ai.mcp.edit')}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <ModalHeader
              title={`${t('ai.mcp.edit')} - ${editModal.server.name}`}
              onClose={handleCloseEdit}
              closeDisabled={updateMutation.isPending || editModal.discoverPending}
            />
            {editModal.loading ? (
              <div className="modal-body">
                <StateBlock title={t('ai.mcp.loadingConfig')} />
              </div>
            ) : editModal.fetchError ? (
              <div className="modal-body">
                <StateBlock
                  title={`${t('ai.mcp.loadConfigFailed')}: ${editModal.fetchError}`}
                  tone="danger"
                />
                <div style={{ marginTop: 12, textAlign: 'center' }}>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => handleOpenEdit(editModal.server)}
                  >
                    {t('shared.confirm')}
                  </button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleUpdateSubmit}>
                <div className="modal-body">
                  <div className="form-group">
                    <FieldLabel required>{t('ai.mcp.configJson')}</FieldLabel>
                    <div className="mcp-json-toolbar">
                      <button
                        type="button"
                        className="ghost-btn btn-sm"
                        onClick={() => {
                          setEditModal({
                            ...editModal,
                            configJson: REMOTE_CONFIG_TEMPLATE,
                            validationMessage: null,
                            validationTone: null,
                            error: null,
                          })
                        }}
                      >
                        {t('ai.mcp.remoteTemplate')}
                      </button>
                      <button
                        type="button"
                        className="ghost-btn btn-sm"
                        onClick={() => {
                          const firstEnvId = environments[0]?.id
                          setEditModal({
                            ...editModal,
                            configJson: createLocalConfigTemplate(firstEnvId),
                            validationMessage: null,
                            validationTone: null,
                            error: null,
                          })
                        }}
                      >
                        {t('ai.mcp.localTemplate')}
                      </button>
                      <button
                        type="button"
                        className="ghost-btn btn-sm"
                        onClick={() => {
                          try {
                            const formatted = formatMcpConfigJson(editModal.configJson)
                            setEditModal({
                              ...editModal,
                              configJson: formatted,
                              validationMessage: null,
                              validationTone: null,
                            })
                          } catch (err) {
                            setEditModal({
                              ...editModal,
                              validationMessage: err instanceof Error ? err.message : String(err),
                              validationTone: 'error',
                            })
                          }
                        }}
                      >
                        {t('ai.mcp.formatJson')}
                      </button>
                      <button
                        type="button"
                        className="ghost-btn btn-sm"
                        onClick={() => {
                          const res = validateMcpConfigJson(editModal.configJson)
                          if (res.valid) {
                            setEditModal({
                              ...editModal,
                              validationMessage: t('ai.mcp.validationPassed'),
                              validationTone: 'success',
                            })
                          } else {
                            setEditModal({
                              ...editModal,
                              validationMessage: res.error ?? 'Invalid JSON',
                              validationTone: 'error',
                            })
                          }
                        }}
                      >
                        {t('ai.mcp.validateJson')}
                      </button>
                    </div>

                    {extractDraftConnectionType(editModal.configJson) === 'local' && (
                      <label className="form-group" style={{ marginBottom: 8 }}>
                        <FieldLabel>{t('ai.mcp.envSelect')}</FieldLabel>
                        <select
                          aria-label={t('ai.mcp.envSelect')}
                          value={extractDraftEnvironmentId(editModal.configJson) ?? ''}
                          onChange={(e) => {
                            const selected = e.target.value
                            if (selected) {
                              const rewritten = updateLocalEnvironmentIdInJson(
                                editModal.configJson,
                                selected,
                              )
                              setEditModal({
                                ...editModal,
                                configJson: rewritten,
                                error: null,
                              })
                            }
                          }}
                        >
                          <option value="" disabled>
                            -- {t('ai.mcp.envSelect')} --
                          </option>
                          {environments.map((env) => (
                            <option key={env.id} value={env.id}>
                              {env.name} ({env.id.slice(0, 8)}...)
                            </option>
                          ))}
                        </select>
                      </label>
                    )}

                    <textarea
                      className="code-textarea mcp-config-json-textarea"
                      aria-label={t('ai.mcp.configJson')}
                      value={editModal.configJson}
                      onChange={(e) =>
                        setEditModal({
                          ...editModal,
                          configJson: e.target.value,
                          validationMessage: null,
                          error: null,
                        })
                      }
                      placeholder="{}"
                      rows={12}
                      required
                    />

                    {editModal.validationMessage && (
                      <div
                        className={`mcp-validation-msg is-${editModal.validationTone ?? 'info'}`}
                        role={editModal.validationTone === 'error' ? 'alert' : 'status'}
                      >
                        {editModal.validationMessage}
                      </div>
                    )}
                  </div>

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
                    onClick={handleCloseEdit}
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
