import { translate } from '@/shared/i18n'

/** 将底层英文/技术错误转成用户可读文案。 */
export function toUserFacingErrorMessage(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error ?? '')
  const text = raw.trim()
  if (!text) {
    return translate('ai.catalog.validation.saveForm')
  }

  const invalidJsonMatch = text.match(
    /^variant\s+(?:["']([^"']+)["']|(\S+))\s+protocolOptions\s+must be valid JSON$/i,
  )
  if (invalidJsonMatch) {
    const id = invalidJsonMatch[1] || invalidJsonMatch[2]
    return translate('ai.catalog.validation.protocolOptionsInvalidJson', { id })
  }
  const notObjectMatch = text.match(
    /^variant\s+(?:["']([^"']+)["']|(\S+))\s+protocolOptions\s+must be a JSON object$/i,
  )
  if (notObjectMatch) {
    const id = notObjectMatch[1] || notObjectMatch[2]
    return translate('ai.catalog.validation.protocolOptionsNotObject', { id })
  }

  const rules: Array<{ match: RegExp; key: string }> = [
    { match: /protocolOptions.*valid JSON/i, key: 'ai.catalog.validation.protocolOptionsInvalidJsonSimple' },
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
    { match: /providerName|请选择 Provider/i, key: 'ai.catalog.validation.provider' },
    {
      match: /agent model name already exists under this provider:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateModel',
    },
    {
      match: /agent provider name already exists:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateProvider',
    },
    {
      match: /agent definition name already exists:?\s*(.*)$/i,
      key: 'ai.catalog.validation.duplicateAgent',
    },
    { match: /pricing|PerMillion|serviceTier|must not be negative|must be a number/i, key: 'ai.catalog.validation.pricing' },
    { match: /modelName|请选择 Model|Default Model/i, key: 'ai.catalog.validation.model' },
    { match: /baseUrl/i, key: 'ai.catalog.validation.baseUrl' },
    { match: /tools.*重名|tools/i, key: 'ai.catalog.validation.toolsConflict' },
    { match: /skills.*重名|skills/i, key: 'ai.catalog.validation.skillsConflict' },
    { match: /Network Error|Failed to fetch|ECONNREFUSED|timeout/i, key: 'ai.catalog.validation.network' },
    { match: /401|Unauthorized/i, key: 'ai.catalog.validation.unauthorized' },
    { match: /403|Forbidden/i, key: 'ai.catalog.validation.unauthorized' },
    { match: /404|Not Found/i, key: 'ai.catalog.validation.notFound' },
    { match: /409|Conflict/i, key: 'ai.catalog.validation.conflict' },
    { match: /500|Internal Server Error/i, key: 'ai.catalog.validation.server' },
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
