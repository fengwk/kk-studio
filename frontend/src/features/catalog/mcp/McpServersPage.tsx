import { useDeferredValue, useMemo, useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { AiConsoleFrame } from '@/app/ai-console/AiConsoleFrame'
import { mcpServerService } from '@/features/catalog/mcp-server-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { CreateMcpServerDialog } from './CreateMcpServerDialog'
import { EditMcpServerDialog } from './EditMcpServerDialog'
import { McpServerCard } from './McpServerCard'
import {
  emptyCreateMcpServerForm,
  type CreateMcpServerFormState,
} from './mcp-server-forms'
import {
  hasUnsavedDiscoverChanges,
  validateCreateMcpServer,
  validateDiscoverEndpoint,
  validateUpdateMcpServer,
} from './mcp-server-form-validation'
import { filterMcpServers } from './mcp-utils'
import { useMcpServerEditor } from './useMcpServerEditor'
import { useMcpServerMutations } from './useMcpServerMutations'
import './mcp-servers.css'
import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'

/**
 * MCP Server 管理页：编排列表查询与搜索、两个编辑弹窗、删除确认与冲突呈现。
 * 页面外壳（导航、搜索、加载/失败状态）复用 `AiConsoleFrame`，与其他 AI 目录页一致；
 * 表单状态、表单规则与写入副作用分别在 `mcp-server-forms`、`mcp-server-form-validation`
 * 与 `useMcpServerEditor` / `useMcpServerMutations` 中，本组件只负责把它们接起来。
 */
export function McpServersPage() {
  const { t } = useI18n()
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())
  const [createForm, setCreateForm] = useState<CreateMcpServerFormState | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<McpServerSummaryDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  const serversQuery = useQuery({
    queryKey: queryKeys.mcpServers.list(),
    queryFn: () => mcpServerService.listServers(),
  })
  const servers = useMemo(
    () => filterMcpServers(serversQuery.data ?? [], deferredSearch),
    [serversQuery.data, deferredSearch],
  )

  const editor = useMcpServerEditor()
  const { editModal } = editor

  function updateCreateForm(update: (form: CreateMcpServerFormState) => CreateMcpServerFormState) {
    setCreateForm((current) => (current ? update(current) : null))
  }

  const mutations = useMcpServerMutations({
    onConflict: setConflict,
    onCreateSettled: () => setCreateForm(null),
    onUpdateSettled: editor.closeEditor,
    onDeleteSettled: () => setDeleteTarget(null),
    updateCreateForm,
    updateEditForm: editor.updateEditModal,
    setDeleteError,
  })
  const { createMutation, updateMutation, deleteMutation } = mutations

  function openCreateDialog() {
    setConflict(null)
    setCreateForm(emptyCreateMcpServerForm())
  }

  function openEditDialog(server: McpServerSummaryDTO) {
    setConflict(null)
    editor.openEditor(server)
  }

  function openDeleteConfirm(server: McpServerSummaryDTO) {
    setConflict(null)
    setDeleteError(null)
    setDeleteTarget(server)
  }

  function requestCloseCreate() {
    if (!createMutation.isPending) {
      setCreateForm(null)
    }
  }

  function requestCloseEdit() {
    if (!updateMutation.isPending && !editModal?.discoverPending) {
      editor.closeEditor()
    }
  }

  function submitCreate(event: FormEvent) {
    event.preventDefault()
    if (!createForm) {
      return
    }
    const validation = validateCreateMcpServer(createForm)
    if (!validation.ok) {
      updateCreateForm((form) => ({ ...form, error: validation.message }))
      return
    }
    createMutation.mutate(validation.value)
  }

  function submitUpdate(event: FormEvent) {
    event.preventDefault()
    if (!editModal?.loadedVersion) {
      return
    }
    const validation = validateUpdateMcpServer(editModal)
    if (!validation.ok) {
      editor.updateEditModal((form) => ({ ...form, error: validation.message }))
      return
    }
    updateMutation.mutate({ name: editModal.server.name, data: validation.value })
  }

  async function discoverTools() {
    if (!editModal?.loadedVersion) {
      return
    }
    setConflict(null)

    const discoverError = validateDiscoverEndpoint(editModal)
    if (discoverError) {
      editor.updateEditModal((form) => ({ ...form, error: discoverError }))
      return
    }
    if (hasUnsavedDiscoverChanges(editModal)) {
      editor.updateEditModal((form) => ({
        ...form,
        error: t('ai.mcp.unsavedDiscoverBlocked'),
      }))
      return
    }

    editor.updateEditModal((form) => ({ ...form, discoverPending: true, error: null }))
    try {
      await mcpServerService.discoverServer(editModal.server.name, editModal.loadedVersion)
      mutations.invalidateServersAndTools()
      editor.closeEditor()
    } catch (err: unknown) {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        editor.closeEditor()
        return
      }
      const message = err instanceof Error ? err.message : String(err)
      editor.updateEditModal((form) => ({ ...form, discoverPending: false, error: message }))
    }
  }

  function refreshAfterConflict() {
    setConflict(null)
    setCreateForm(null)
    editor.closeEditor()
    setDeleteTarget(null)
    setDeleteError(null)
    mutations.invalidateServersAndTools()
  }

  return (
    <AiConsoleFrame
      search={search}
      onSearchChange={setSearch}
      busy={serversQuery.isLoading}
      error={serversQuery.error}
      onRetry={() => void serversQuery.refetch()}
      hasCachedList={serversQuery.data !== undefined}
      mutationError={null}
      content={
        <ResourceGrid>
          <CreateCard
            title={t('ai.mcp.create')}
            subtitle={t('ai.mcp.createDescription')}
            onClick={openCreateDialog}
          />
          {servers.map((server) => (
            <McpServerCard
              key={server.name}
              server={server}
              deletePending={deleteMutation.isPending && deleteTarget?.name === server.name}
              onEdit={() => openEditDialog(server)}
              onDelete={() => openDeleteConfirm(server)}
            />
          ))}
        </ResourceGrid>
      }
    >
      {createForm && (
        <CreateMcpServerDialog
          form={createForm}
          pending={createMutation.isPending}
          onChange={setCreateForm}
          onSubmit={submitCreate}
          onRequestClose={requestCloseCreate}
        />
      )}

      {editModal && (
        <EditMcpServerDialog
          form={editModal}
          saving={updateMutation.isPending}
          onChange={(form) => editor.updateEditModal(() => form)}
          onSubmit={submitUpdate}
          onDiscover={discoverTools}
          onRetryLoad={() => editor.openEditor(editModal.server)}
          onRequestClose={requestCloseEdit}
        />
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
        onRefresh={refreshAfterConflict}
        onClose={() => setConflict(null)}
      />
    </AiConsoleFrame>
  )
}
