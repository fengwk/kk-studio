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
interface LoadedMcpServerForm {
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

/**
 * 新建草稿是否偏离打开时的空表单基线。
 *
 * 逐字段比较原值（超时也包括在内）：清空超时仍是用户对草稿的修改。
 */
export function hasUnsavedCreateChanges(form: CreateMcpServerFormState): boolean {
  const baseline = emptyCreateMcpServerForm()
  return (
    form.name !== baseline.name ||
    form.url !== baseline.url ||
    form.headersText !== baseline.headersText ||
    form.enabled !== baseline.enabled ||
    form.timeoutMillis !== baseline.timeoutMillis
  )
}

/** 关闭编辑器时比较原始草稿；提交与发现工具的配置归一化不适用于此处。 */
export function hasUnsavedEditChanges(form: EditMcpServerFormState): boolean {
  const { loaded } = form
  return loaded !== null && (
    form.url !== loaded.url ||
    form.enabled !== loaded.enabled ||
    form.timeoutMillis !== loaded.timeoutMillis ||
    form.headersText !== '' ||
    form.clearHeaders
  )
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
