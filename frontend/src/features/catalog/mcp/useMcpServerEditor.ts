import { useEffect, useRef, useState } from 'react'
import { mcpServerService } from '@/features/catalog/mcp-server-service'
import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'
import {
  pendingEditMcpServerForm,
  type EditMcpServerFormState,
} from './mcp-server-forms'

/**
 * 编辑表单的加载生命周期：打开时用摘要占位，详情到达后填入已加载快照。
 *
 * 读取详情是异步的，关闭或重新打开同一 server 必须让迟到响应失效；
 * 用单调递增的请求代际 token 表达，关闭、重新打开与保存成功都会推进它。
 */
export function useMcpServerEditor() {
  const [editModal, setEditModal] = useState<EditMcpServerFormState | null>(null)
  const requestGenerationRef = useRef(0)

  useEffect(() => {
    const generationRef = requestGenerationRef
    return () => {
      generationRef.current++
    }
  }, [])

  function updateEditModal(
    update: (current: EditMcpServerFormState) => EditMcpServerFormState,
  ) {
    setEditModal((current) => (current ? update(current) : null))
  }

  /** 关闭编辑器并作废在途的详情请求。 */
  function closeEditor() {
    requestGenerationRef.current++
    setEditModal(null)
  }

  function openEditor(server: McpServerSummaryDTO) {
    const generation = ++requestGenerationRef.current
    setEditModal(pendingEditMcpServerForm(server))

    mcpServerService
      .getServer(server.name)
      .then((detail) => {
        if (requestGenerationRef.current !== generation) {
          return
        }
        updateEditModal((current) => ({
          ...current,
          loading: false,
          loadedVersion: detail.version,
          loaded: {
            url: detail.url,
            enabled: detail.enabled,
            timeoutMillis: detail.timeoutMillis,
            headersConfigured: detail.headers.configured,
          },
          url: detail.url,
          headersText: '',
          clearHeaders: false,
          enabled: detail.enabled,
          timeoutMillis: detail.timeoutMillis,
        }))
      })
      .catch((err: unknown) => {
        if (requestGenerationRef.current !== generation) {
          return
        }
        updateEditModal((current) => ({
          ...current,
          loading: false,
          fetchError: err instanceof Error ? err.message : String(err),
        }))
      })
  }

  return {
    editModal,
    updateEditModal,
    openEditor,
    /** 关闭编辑器并作废在途的详情请求；保存成功后也走这里。 */
    closeEditor,
  }
}
