import {
  parseAndValidateHeaders,
  validateMcpName,
  validateMcpUrl,
  validateTimeoutMillis,
} from './mcp-utils'
import { translate } from '@/shared/i18n'
import type { CreateMcpServerFormState, EditMcpServerFormState } from './mcp-server-forms'

export interface ValidatedCreateMcpServer {
  name: string
  url: string
  headers: Record<string, string>
  enabled: boolean
  timeoutMillis: string
}

export interface ValidatedUpdateMcpServer {
  expectedVersion: string
  url: string
  headers: Record<string, string>
  clearHeaders: boolean
  enabled: boolean
  timeoutMillis: string
}

/** 校验通过返回要提交的数据，失败返回第一条可展示的错误文案。 */
export type ValidationOutcome<T> = { ok: true; value: T } | { ok: false; message: string }

/** 创建与更新共用的字段规则：URL、headers 文本与超时。 */
function validateEndpoint(
  url: string,
  headersText: string,
  clearHeaders: boolean,
  timeoutMillis: string,
): ValidationOutcome<{
  url: string
  headers: Record<string, string>
  clearHeaders: boolean
  timeoutMillis: string
}> {
  const urlResult = validateMcpUrl(url)
  if (!urlResult.valid) {
    return { ok: false, message: urlResult.error ?? translate('ai.mcp.validation.urlInvalid') }
  }
  const headersResult = parseAndValidateHeaders(clearHeaders ? '' : headersText)
  if (!headersResult.valid) {
    return {
      ok: false,
      message: headersResult.error ?? translate('ai.mcp.validation.headersInvalidJson'),
    }
  }
  const timeoutResult = validateTimeoutMillis(timeoutMillis)
  if (!timeoutResult.valid) {
    return {
      ok: false,
      message: timeoutResult.error ?? translate('ai.mcp.validation.timeoutPositive'),
    }
  }
  return {
    ok: true,
    value: {
      url,
      headers: headersResult.headers,
      clearHeaders,
      timeoutMillis: timeoutResult.timeoutMillis,
    },
  }
}

export function validateCreateMcpServer(
  form: CreateMcpServerFormState,
): ValidationOutcome<ValidatedCreateMcpServer> {
  const nameResult = validateMcpName(form.name)
  if (!nameResult.valid) {
    return { ok: false, message: nameResult.error ?? translate('ai.mcp.validation.nameRequired') }
  }
  const endpoint = validateEndpoint(form.url, form.headersText, false, form.timeoutMillis)
  if (!endpoint.ok) {
    return endpoint
  }
  return {
    ok: true,
    value: {
      name: form.name.trim(),
      url: endpoint.value.url,
      headers: endpoint.value.headers,
      enabled: form.enabled,
      timeoutMillis: endpoint.value.timeoutMillis,
    },
  }
}

/** 更新必须携带详情投影里的 version；缺失说明详情还没加载完，不能提交。 */
export function validateUpdateMcpServer(
  form: EditMcpServerFormState,
): ValidationOutcome<ValidatedUpdateMcpServer> {
  if (!form.loadedVersion) {
    return { ok: false, message: '' }
  }
  const endpoint = validateEndpoint(form.url, form.headersText, form.clearHeaders, form.timeoutMillis)
  if (!endpoint.ok) {
    return endpoint
  }
  return {
    ok: true,
    value: {
      expectedVersion: form.loadedVersion,
      url: endpoint.value.url,
      headers: endpoint.value.headers,
      clearHeaders: endpoint.value.clearHeaders,
      enabled: form.enabled,
      timeoutMillis: endpoint.value.timeoutMillis,
    },
  }
}

/**
 * 发现工具前必须提交的是“已保存的配置”：headers 从不回读，
 * 任何一次替换或清除都视为未保存的变更。
 *
 * 超时按归一后的值比较：空输入提交时会回退为默认值，与已保存值相同就不算改动。
 * 调用方须先通过 `validateDiscoverEndpoint`，非法输入在那里已被拒绝。
 */
export function hasUnsavedDiscoverChanges(form: EditMcpServerFormState): boolean {
  if (!form.loaded) {
    return false
  }
  const headersDirty = form.headersText.trim().length > 0 || form.clearHeaders
  return (
    form.url !== form.loaded.url ||
    form.enabled !== form.loaded.enabled ||
    validateTimeoutMillis(form.timeoutMillis).timeoutMillis !== form.loaded.timeoutMillis ||
    headersDirty
  )
}

/** 发现工具只校验会被服务端回读的 headers 与超时；URL 不在提交范围内。 */
export function validateDiscoverEndpoint(form: EditMcpServerFormState): string | null {
  const headersResult = parseAndValidateHeaders(form.clearHeaders ? '' : form.headersText)
  if (!headersResult.valid) {
    return headersResult.error ?? translate('ai.mcp.validation.headersInvalidJson')
  }
  const timeoutResult = validateTimeoutMillis(form.timeoutMillis)
  if (!timeoutResult.valid) {
    return timeoutResult.error ?? translate('ai.mcp.validation.timeoutPositive')
  }
  return null
}
