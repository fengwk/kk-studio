import { translate, type TranslationKey } from '@/shared/i18n'
import { ApiError, apiErrorDetail } from '@/shared/api/client'

/**
 * 从任意错误中取出可展示的失败详情：后端去敏 detail 优先，其次 Error/字符串文本。
 * 没有任何可展示文本时返回 null，由调用方决定兜底文案。
 */
export function errorDetailMessage(error: unknown): string | null {
  const detail = apiErrorDetail(error)
  if (detail != null) {
    return detail
  }
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message
  }
  if (typeof error === 'string' && error.trim().length > 0) {
    return error
  }
  return null
}

/**
 * HTTP 失败只看结构化 status/code：401/403、404、409 与 5xx 各有独立文案，
 * 其余 4xx（字段校验）返回 null，由去敏 detail 继续决定字段文案。
 * 传输层失败没有 status，client 用稳定 code 表达。
 */
function apiErrorCategoryKey(error: unknown): TranslationKey | null {
  if (!(error instanceof ApiError)) {
    return null
  }
  if (error.status === 401 || error.status === 403) {
    return 'ai.catalog.validation.unauthorized'
  }
  if (error.status === 404) {
    return 'ai.catalog.validation.notFound'
  }
  if (error.status === 409) {
    return 'ai.catalog.validation.conflict'
  }
  if (error.status !== undefined) {
    return error.status >= 500 ? 'ai.catalog.validation.server' : null
  }
  return error.code === 'network_error' || error.code === 'timeout'
    ? 'ai.catalog.validation.network'
    : null
}

/**
 * 将失败转成用户可读文案。
 *
 * HTTP 失败（{@link ApiError}）按 status/code 分类，标题文本不参与判断；
 * 只有本地表单校验（codec 抛出的英文文本）才按文本匹配字段文案。
 */
export function toUserFacingErrorMessage(error: unknown): string {
  const categoryKey = apiErrorCategoryKey(error)
  if (categoryKey) {
    return translate(categoryKey)
  }

  const text = errorDetailMessage(error)?.trim() ?? ''
  if (!text) {
    return translate('ai.catalog.validation.saveForm')
  }

  const invalidJsonMatch = text.match(
    /^variant\s+(?:["']([^"']+)["']|(\S+))\s+protocolOptionsJson\s+must be valid JSON$/i,
  )
  if (invalidJsonMatch) {
    const id = invalidJsonMatch[1] || invalidJsonMatch[2]
    return translate('ai.catalog.validation.protocolOptionsInvalidJson', { id })
  }
  const notObjectMatch = text.match(
    /^variant\s+(?:["']([^"']+)["']|(\S+))\s+protocolOptionsJson\s+must be a JSON object$/i,
  )
  if (notObjectMatch) {
    const id = notObjectMatch[1] || notObjectMatch[2]
    return translate('ai.catalog.validation.protocolOptionsNotObject', { id })
  }

  const rules: Array<{ match: RegExp; key: TranslationKey }> = [
    { match: /protocolOptionsJson.*65536/i, key: 'ai.catalog.validation.protocolOptionsTooLarge' },
    { match: /protocolOptionsJson.*(?:is invalid|must be a JSON string)/i, key: 'ai.catalog.validation.protocolOptionsStrict' },
    { match: /protocolOptionsJson.*valid JSON/i, key: 'ai.catalog.validation.protocolOptionsInvalidJsonSimple' },
    { match: /protocolOptions.*JSON object/i, key: 'ai.catalog.validation.protocolOptionsNotObjectSimple' },
    { match: /reasoningEffort|思考强度/i, key: 'ai.catalog.validation.reasoningEnabled' },
    { match: /^variant must not be blank$/i, key: 'ai.catalog.validation.variantRequired' },
    { match: /at least one variant|variant \d+ id is required/i, key: 'ai.catalog.validation.variantAddOne' },
    { match: /duplicate variant/i, key: 'ai.catalog.validation.variantDuplicate' },
    { match: /defaultVariant/i, key: 'ai.catalog.validation.defaultVariant' },
    { match: /maxOutputTokens.*exceed|must not exceed context/i, key: 'ai.catalog.validation.maxOutputContext' },
    { match: /contextWindow|limit\.context/i, key: 'ai.catalog.validation.contextWindow' },
    { match: /maxOutputTokens|limit\.output/i, key: 'ai.catalog.validation.maxOutput' },
    { match: /inputModalit|modality/i, key: 'ai.catalog.validation.inputModality' },
    { match: /modelId/i, key: 'ai.catalog.validation.modelId' },
    { match: /providerType|provider type/i, key: 'ai.catalog.validation.providerType' },
    // Provider 超时是本地输入校验，必须给出整数提示；"timeout" 字样不能被当成网络故障。
    {
      match: /canonical non-negative integer/i,
      key: 'ai.catalog.validation.providerTimeout',
    },
    { match: /providerName|请选择 Provider/i, key: 'ai.catalog.validation.provider' },
    {
      match: /model name already exists:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateModel',
    },
    {
      match: /provider name already exists:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateProvider',
    },
    {
      match: /agent name already exists:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateAgent',
    },
    { match: /pricing|PerMillion|serviceTier|must not be negative|must be a number/i, key: 'ai.catalog.validation.pricing' },
    { match: /modelName|请选择 Model|Default Model/i, key: 'ai.catalog.validation.model' },
    { match: /baseUrl/i, key: 'ai.catalog.validation.baseUrl' },
    { match: /tools.*重名|tools/i, key: 'ai.catalog.validation.toolsConflict' },
    { match: /skills.*重名|skills/i, key: 'ai.catalog.validation.skillsConflict' },
  ]

  for (const rule of rules) {
    if (rule.match.test(text)) {
      return translate(rule.key)
    }
  }

  if (/[\u4e00-\u9fff]/.test(text)) {
    return text
  }
  if (
    /^(agent |invalid |unknown |stored |config\.|provider |model |chat )/i.test(text) ||
    /already exists|must not|must be|is required|not found|in use/i.test(text)
  ) {
    return text
  }
  return translate('ai.catalog.validation.saveRequired')
}
