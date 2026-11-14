import type { McpServerSummaryDTO } from '@/shared/api/generated/contracts'
import { DEFAULT_TIMEOUT_MILLIS } from './mcp-utils'

/** 新建表单：headers 只接受整体替换文本，不区分“保持/清除”。 */
export interface CreateMcpServerFormState {
  name: string
  url: string
  headersText: string
  enabled: boolean
  timeoutMillis: string
  error: string | null
}

/** headers 从不回读；这里只记录“已配置”的事实与脏检查所需的已加载快照。 */
export interface LoadedMcpServerForm {
  url: string
  enabled: boolean
  timeoutMillis: string
  headersConfigured: boolean
}

export interface EditMcpServerFormState {
  server: McpServerSummaryDTO
  loading: boolean
  fetchError: string | null
  loadedVersion: string | null
  loaded: LoadedMcpServerForm | null
  url: string
  headersText: string
  clearHeaders: boolean
  enabled: boolean
  timeoutMillis: string
  error: string | null
  discoverPending: boolean
}

export function emptyCreateMcpServerForm(): CreateMcpServerFormState {
  return {
    name: '',
    url: '',
    headersText: '',
    enabled: true,
    timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
    error: null,
  }
}

/** 打开编辑器时的占位表单：详情到达前只呈现 loading，并沿用列表摘要里的开关与超时。 */
export function pendingEditMcpServerForm(server: McpServerSummaryDTO): EditMcpServerFormState {
  return {
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
  }
}
