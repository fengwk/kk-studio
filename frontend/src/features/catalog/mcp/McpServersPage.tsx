import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { ResourceCardLayout } from '@/features/catalog/AiResourceCardLayout'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Button } from '@/shared/ui/controls/Button'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { LoadingIndicator } from '@/shared/ui/feedback/LoadingSpinner'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { headersUpdate, mcpServerService } from '@/features/catalog/mcp-server-service'
import { AiNavigation } from '@/app/ai-console/AiNavigation'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { formatDateTime24 } from '@/shared/lib/date-format'
import {
  DEFAULT_TIMEOUT_MILLIS,
  parseAndValidateHeaders,
  validateMcpName,
  validateMcpUrl,
  validateTimeoutMillis,
} from './mcp-utils'
import './mcp-servers.css'
import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'

interface CreateModalState {
  name: string
  url: string
  headersText: string
  enabled: boolean
  timeoutMillis: string
  error: string | null
}

interface EditModalState {
  server: McpServerSummaryDTO
  loading: boolean
  fetchError: string | null
  loadedVersion: string | null
  loaded: {
    url: string
    enabled: boolean
    timeoutMillis: string
    headersConfigured: boolean
  } | null
  url: string
  headersText: string
  clearHeaders: boolean
  enabled: boolean
  timeoutMillis: string
  error: string | null
  discoverPending: boolean
}

export function McpServersPage() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()

  const [createModal, setCreateModal] = useState<CreateModalState | null>(null)
  const [editModal, setEditModal] = useState<EditModalState | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<McpServerSummaryDTO | null>(null)
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
    queryKey: queryKeys.mcpServers.list(),
    queryFn: () => mcpServerService.listServers(),
  })

  const servers: McpServerSummaryDTO[] = serversQuery.data ?? []

  const createMutation = useMutation({
    mutationFn: (data: {
      name: string
      url: string
      headers: Record<string, string>
      enabled: boolean
      timeoutMillis: string
    }) =>
      mcpServerService.createServer({
        name: data.name,
        url: data.url,
        // 创建只接受 SET：没有 header 时省略字段即未配置，KEEP/CLEAR 在创建时无效。
        ...(Object.keys(data.headers).length > 0
          ? { headers: headersUpdate(data.headers, false) }
          : {}),
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
        clearHeaders: boolean
        enabled: boolean
        timeoutMillis: string
      }
    }) =>
      mcpServerService.updateServer(args.name, {
        expectedVersion: args.data.expectedVersion,
        url: args.data.url,
        headers: headersUpdate(args.data.headers, args.data.clearHeaders),
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

  function handleOpenEdit(server: McpServerSummaryDTO) {
    setConflict(null)
    const generation = ++editRequestGenerationRef.current
    setEditModal({
      server,
      loading: true,
      fetchError: null,
      loadedVersion: null,
      loaded: null,
      url: '',
      headersText: '',
      clearHeaders: false,
      enabled: server.enabled,
      timeoutMillis: server.timeoutMillis,
      error: null,
      discoverPending: false,
    })

    mcpServerService
      .getServer(server.name)
      .then((detail) => {
        if (editRequestGenerationRef.current !== generation) {
          return
        }
        const timeoutMillis = detail.timeoutMillis
        setEditModal((prev) => {
          if (!prev || editRequestGenerationRef.current !== generation) return null
          return {
            ...prev,
            loading: false,
            loadedVersion: detail.version,
            loaded: {
              url: detail.url,
              enabled: detail.enabled,
              timeoutMillis,
              headersConfigured: detail.headers.configured,
            },
            url: detail.url,
            headersText: '',
            clearHeaders: false,
            enabled: detail.enabled,
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

    const headersVal = parseAndValidateHeaders(
      editModal.clearHeaders ? '' : editModal.headersText,
    )
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
        clearHeaders: editModal.clearHeaders,
        enabled: editModal.enabled,
        timeoutMillis: timeoutVal.timeoutMillis,
      },
    })
  }

  async function handleDiscover() {
    if (!editModal || !editModal.loadedVersion) return
    setConflict(null)

    const headersVal = parseAndValidateHeaders(
      editModal.clearHeaders ? '' : editModal.headersText,
    )
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

    // headers 不回读，任何一次提交（替换或清除）都视为未保存的配置变更
    const headersDirty = editModal.headersText.trim().length > 0 || editModal.clearHeaders
    if (
      editModal.loaded &&
      (editModal.url !== editModal.loaded.url ||
        editModal.enabled !== editModal.loaded.enabled ||
        timeoutVal.timeoutMillis !== editModal.loaded.timeoutMillis ||
        headersDirty)
    ) {
      setEditModal((prev) =>
        prev ? { ...prev, error: t('ai.mcp.unsavedDiscoverBlocked') } : null,
      )
      return
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
        <AiNavigation />
      </nav>

      <div className="screen-body">
        {serversQuery.isLoading && <ResourceCardSkeleton label={t('ai.mcp.loading')} />}
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
        {!serversQuery.isLoading && (
          <ResourceGrid>
            <CreateCard
              title={t('ai.mcp.create')}
              subtitle={t('ai.mcp.createDescription')}
              onClick={handleOpenCreate}
            />
          {servers.map((server: McpServerSummaryDTO) => {
            const updateTime = formatDateTime24(new Date(server.updatedAt), locale)

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
        </ResourceGrid>
      )}
      </div>

      {createModal && (
        <Dialog
          className="mcp-modal-card"
          title={t('ai.mcp.create')}
          pending={createMutation.isPending}
          onClose={handleRequestCloseCreate}
        >
            <form className="modal-card-form" onSubmit={handleCreateSubmit}>
              <div className="modal-body">
                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.name')}</FieldLabel>
                  <TextInput
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
                    Lowercase letters, numbers, and underscores. Pattern: ^[a-z][a-z0-9_]*$
                  </span>
                </label>

                <label className="form-group">
                  <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                  <TextInput
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
                    Streamable HTTP endpoint URL. Use http:// or https://.
                  </span>
                </label>

                <label className="form-group">
                  <FieldLabel>{t('ai.mcp.headers')}</FieldLabel>
                  <TextArea
                    className="mcp-headers-textarea"
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
                  <NumberInput
                    value={String(createModal.timeoutMillis)}
                    min={1}
                    onChange={(timeoutMillis) =>
                      setCreateModal({ ...createModal, timeoutMillis, error: null })
                    }
                    aria-label={t('ai.catalog.card.timeout')}
                    disabled={createMutation.isPending}
                  />
                </label>

                <Checkbox
                  checked={createModal.enabled}
                  onChange={(enabled) => setCreateModal({ ...createModal, enabled, error: null })}
                  disabled={createMutation.isPending}
                  label={t('ai.mcp.enabled')}
                />

                {createModal.error && (
                  <p className="field-error" role="alert">
                    {createModal.error}
                  </p>
                )}
              </div>

              <div className="modal-footer">
                <Button
                  variant="ghost"
                  onClick={handleRequestCloseCreate}
                  disabled={createMutation.isPending}
                >
                  {t('shared.cancel')}
                </Button>
                <Button type="submit" loading={createMutation.isPending}>
                  {t('shared.confirm')}
                </Button>
              </div>
            </form>
        </Dialog>
      )}

      {editModal && (
        <Dialog
          className="mcp-modal-card"
          title={`${t('ai.mcp.edit')} · ${editModal.server.name}`}
          pending={updateMutation.isPending || editModal.discoverPending}
          onClose={handleRequestCloseEdit}
        >
            {editModal.loading ? (
              <div className="modal-body">
                <LoadingIndicator label={t('ai.mcp.loadingConfig')} />
              </div>
            ) : editModal.fetchError ? (
              <div className="modal-body">
                <StateBlock
                  title={editModal.fetchError || t('ai.mcp.loadConfigFailed')}
                  tone="danger"
                />
                <div style={{ marginTop: 12, textAlign: 'center' }}>
                  <Button variant="ghost" onClick={() => handleOpenEdit(editModal.server)}>
                    {t('shared.retry')}
                  </Button>
                </div>
              </div>
            ) : (
              <form className="modal-card-form" onSubmit={handleUpdateSubmit}>
                <div className="modal-body">
                  <label className="form-group">
                    <FieldLabel>{t('ai.mcp.name')}</FieldLabel>
                    <TextInput
                      value={editModal.server.name}
                      disabled
                      readOnly
                    />
                  </label>

                  <label className="form-group">
                    <FieldLabel required>{t('ai.mcp.url')}</FieldLabel>
                    <TextInput
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
                    <TextArea
                      className="mcp-headers-textarea"
                      value={editModal.headersText}
                      onChange={(e) =>
                        setEditModal({
                          ...editModal,
                          headersText: e.target.value,
                          clearHeaders: false,
                          error: null,
                        })
                      }
                      placeholder={'{\n  "Authorization": "Bearer ${AUTH_TOKEN}"\n}'}
                      disabled={
                        updateMutation.isPending ||
                        editModal.discoverPending ||
                        editModal.clearHeaders
                      }
                      rows={4}
                    />
                    <span className="inline-hint">
                      {editModal.loaded?.headersConfigured
                        ? t('ai.mcp.headersKeepHint')
                        : t('ai.mcp.headersHint')}
                    </span>
                    {editModal.loaded?.headersConfigured && (
                      <Checkbox
                        checked={editModal.clearHeaders}
                        onChange={(clearHeaders) =>
                          setEditModal({
                            ...editModal,
                            clearHeaders,
                            headersText: clearHeaders ? '' : editModal.headersText,
                            error: null,
                          })
                        }
                        disabled={updateMutation.isPending || editModal.discoverPending}
                        label={t('ai.mcp.clearHeaders')}
                      />
                    )}
                  </label>

                  <label className="form-group">
                    <FieldLabel>{t('ai.catalog.card.timeout')}</FieldLabel>
                    <NumberInput
                      value={String(editModal.timeoutMillis)}
                      min={1}
                      onChange={(timeoutMillis) =>
                        setEditModal({ ...editModal, timeoutMillis, error: null })
                      }
                      aria-label={t('ai.catalog.card.timeout')}
                      disabled={updateMutation.isPending || editModal.discoverPending}
                    />
                  </label>

                  <Checkbox
                    checked={editModal.enabled}
                    onChange={(enabled) => setEditModal({ ...editModal, enabled, error: null })}
                    disabled={updateMutation.isPending || editModal.discoverPending}
                    label={t('ai.mcp.enabled')}
                  />

                  {editModal.error && (
                    <p className="field-error" role="alert">
                      {editModal.error}
                    </p>
                  )}
                </div>

                <div className="modal-footer modal-footer-with-leading-action">
                  <Button
                    variant="ghost"
                    className="modal-footer-leading-action"
                    onClick={handleDiscover}
                    disabled={updateMutation.isPending}
                    loading={editModal.discoverPending}
                  >
                    {!editModal.discoverPending && <RefreshCw aria-hidden="true" />}
                    {t('ai.mcp.discover')}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={handleRequestCloseEdit}
                    disabled={updateMutation.isPending || editModal.discoverPending}
                  >
                    {t('shared.cancel')}
                  </Button>
                  <Button
                    type="submit"
                    disabled={editModal.discoverPending}
                    loading={updateMutation.isPending}
                  >
                    {t('shared.confirm')}
                  </Button>
                </div>
              </form>
            )}
        </Dialog>
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
