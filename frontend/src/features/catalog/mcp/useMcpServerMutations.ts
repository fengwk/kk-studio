import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { headersUpdate, mcpServerService } from '@/features/catalog/mcp-server-service'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'
import { queryKeys } from '@/shared/lib/query-keys'
import type { CreateMcpServerFormState, EditMcpServerFormState } from './mcp-server-forms'

export interface CreateMcpServerPayload {
  name: string
  url: string
  headers: Record<string, string>
  enabled: boolean
  timeoutMillis: string
}

export interface UpdateMcpServerPayload {
  name: string
  data: {
    expectedVersion: string
    url: string
    headers: Record<string, string>
    clearHeaders: boolean
    enabled: boolean
    timeoutMillis: string
  }
}

export interface DeleteMcpServerPayload {
  name: string
  expectedVersion: string
}

function failureMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/**
 * MCP Server 的写入面：创建、更新与删除。
 *
 * 每个操作的 409 都收敛成同一种 conflict 呈现，其余错误留在各自的表单或删除确认里；
 * 写入成功后失效 list（更新与删除还失效工具清单，因为服务会带来工具）。
 */
export function useMcpServerMutations(options: {
  onConflict: (conflict: ConflictPresentation | null) => void
  onCreateSettled: () => void
  onUpdateSettled: () => void
  onDeleteSettled: () => void
  updateCreateForm: (update: (form: CreateMcpServerFormState) => CreateMcpServerFormState) => void
  updateEditForm: (update: (form: EditMcpServerFormState) => EditMcpServerFormState) => void
  setDeleteError: (message: string | null) => void
}) {
  const queryClient = useQueryClient()
  const {
    onConflict,
    onCreateSettled,
    onUpdateSettled,
    onDeleteSettled,
    updateCreateForm,
    updateEditForm,
    setDeleteError,
  } = options

  function invalidateServers() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all })
  }

  function invalidateServersAndTools() {
    invalidateServers()
    void queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
  }

  const createMutation = useMutation({
    mutationFn: (data: CreateMcpServerPayload) =>
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
      onCreateSettled()
      invalidateServers()
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        onConflict(presentConflict(err))
        onCreateSettled()
        return
      }
      updateCreateForm((form) => ({ ...form, error: failureMessage(err) }))
    },
  })

  const updateMutation = useMutation({
    mutationFn: (args: UpdateMcpServerPayload) =>
      mcpServerService.updateServer(args.name, {
        expectedVersion: args.data.expectedVersion,
        url: args.data.url,
        headers: headersUpdate(args.data.headers, args.data.clearHeaders),
        enabled: args.data.enabled,
        timeoutMillis: args.data.timeoutMillis,
      }),
    onSuccess: () => {
      onUpdateSettled()
      invalidateServersAndTools()
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        onConflict(presentConflict(err))
        onUpdateSettled()
        return
      }
      updateEditForm((form) => ({ ...form, error: failureMessage(err) }))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (args: DeleteMcpServerPayload) =>
      mcpServerService.deleteServer(args.name, args.expectedVersion),
    onSuccess: () => {
      onDeleteSettled()
      invalidateServersAndTools()
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        onConflict(presentConflict(err))
        onDeleteSettled()
        setDeleteError(null)
        return
      }
      setDeleteError(failureMessage(err))
    },
  })

  return { createMutation, updateMutation, deleteMutation, invalidateServersAndTools }
}
