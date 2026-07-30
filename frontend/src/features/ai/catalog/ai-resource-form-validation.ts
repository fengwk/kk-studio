import type { AgentDraft, ModelDraft, ProviderDraft, ResourceModal } from '@/features/ai/catalog/ai-console-types'
import {
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
} from '@/features/ai/catalog/ai-resource-draft-codecs'

export type ResourceFieldKey =
  | 'name'
  | 'providerId'
  | 'baseUrl'
  | 'contextWindow'
  | 'maxOutputTokens'
  | 'inputModalities'
  | 'variants'
  | 'reasoningEffort'
  | 'defaultVariant'
  | 'pricing'
  | 'modelId'
  | 'variant'
  | 'environmentName'
  | 'tools'
  | 'skills'
  | 'general'

export interface ResourceFormValidationResult {
  ok: boolean
  message: string
  fields: Partial<Record<ResourceFieldKey, string>>
}

/** 将底层英文/技术错误转成用户可读中文。 */
export function toUserFacingErrorMessage(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error ?? '')
  const text = raw.trim()
  if (!text) {
    return '保存失败，请检查表单后重试'
  }

  const rules: Array<{ match: RegExp; message: string }> = [
    { match: /reasoningEffort|思考强度/i, message: '已开启 Reasoning，请为每个配置填写思考强度（如 low / medium / high）' },
    { match: /^variant must not be blank$/i, message: '请选择有效的 Variant' },
    { match: /at least one variant|variant \d+ id is required/i, message: '请至少添加一个 Variant，并填写 ID' },
    { match: /duplicate variant/i, message: 'Variant ID 不能重复' },
    { match: /defaultVariant/i, message: '请选择一个有效的默认 Variant' },
    { match: /maxOutputTokens.*exceed|must not exceed context/i, message: '最大输出长度不能超过上下文窗口' },
    { match: /contextWindow|limit\.context/i, message: '请填写有效的上下文窗口（正整数）' },
    { match: /maxOutputTokens|limit\.output/i, message: '请填写有效的最大输出长度（正整数）' },
    { match: /inputModalit|modality/i, message: '请至少选择一种输入类型（建议保留 TEXT）' },
    { match: /temperature/i, message: 'Temperature 必须为 0 或正数' },
    { match: /topP/i, message: 'Top P 必须大于 0 且不超过 1' },
    { match: /topK/i, message: 'Top K 必须为正整数' },
    { match: /frequencyPenalty|presencePenalty/i, message: 'Penalty 必须为有效数字' },
    { match: /providerId|请选择 Provider/i, message: '请选择 Provider' },
    // 名称冲突：优先于通用 409/Conflict，便于用户理解「同 Provider 下重名」
    {
      match: /agent model name already exists( under this provider)?:?\s*(.*)$/i,
      message: '当前 Provider 下已存在同名 Model，请换一个名称',
    },
    {
      match: /agent provider name already exists:?\s*(.*)$/i,
      message: 'Provider 名称已存在，请换一个名称',
    },
    {
      match: /agent definition name already exists:?\s*(.*)$/i,
      message: 'Agent 名称已存在，请换一个名称',
    },
    { match: /pricing|PerMillion|serviceTier|must not be negative|must be a number/i, message: '请检查价格：填写 0 或正数即可' },
    { match: /modelId|请选择 Model|Default Model/i, message: '请选择 Default Model' },
    { match: /baseUrl/i, message: '请填写 Base URL' },
    { match: /tools.*重名|tools/i, message: 'Tools 名称冲突，请检查勾选项' },
    { match: /skills.*重名|skills/i, message: 'Skills 名称冲突，请检查勾选项' },
    { match: /Network Error|Failed to fetch|ECONNREFUSED|timeout/i, message: '网络异常，请稍后重试' },
    { match: /401|Unauthorized/i, message: '没有权限执行此操作' },
    { match: /403|Forbidden/i, message: '没有权限执行此操作' },
    { match: /404|Not Found/i, message: '资源不存在或已被删除' },
    { match: /409|Conflict/i, message: '资源冲突，请刷新后重试' },
    { match: /500|Internal Server Error/i, message: '服务暂时异常，请稍后重试' },
  ]

  for (const rule of rules) {
    if (rule.match.test(text)) {
      return rule.message
    }
  }

  // 已是中文则直接展示
  if (/[\u4e00-\u9fff]/.test(text)) {
    return text
  }
  // 后端业务英文错误（非通用 HTTP 短语）直接透出，避免吞成笼统文案
  if (
    /^(agent |invalid |unknown |stored |config\.|provider |model |chat )/i.test(text) ||
    /already exists|must not|must be|is required|not found|in use/i.test(text)
  ) {
    return text
  }
  return '保存失败，请检查必填项后重试'
}

function mapError(error: unknown): ResourceFormValidationResult {
  const message = toUserFacingErrorMessage(error)
  const raw = error instanceof Error ? error.message : String(error ?? '')

  if (/reasoningEffort|思考强度/i.test(raw) || /思考强度/.test(message)) {
    return {
      ok: false,
      message,
      fields: { reasoningEffort: '请填写思考强度', variants: message },
    }
  }
  if (/^variant must not be blank$/i.test(raw)) {
    return { ok: false, message, fields: { variant: message } }
  }
  if (/temperature|topP|topK|frequencyPenalty|presencePenalty/i.test(raw)) {
    return { ok: false, message, fields: { variants: message } }
  }
  if (/variant|defaultVariant/i.test(raw)) {
    return { ok: false, message, fields: { variants: message, defaultVariant: message } }
  }
  if (/maxOutputTokens|limit\.output|exceed/i.test(raw)) {
    return { ok: false, message, fields: { maxOutputTokens: message } }
  }
  if (/contextWindow|limit\.context/i.test(raw)) {
    return { ok: false, message, fields: { contextWindow: message } }
  }
  if (/modality/i.test(raw)) {
    return { ok: false, message, fields: { inputModalities: message } }
  }
  if (/pricing|PerMillion|number|negative/i.test(raw)) {
    return { ok: false, message, fields: { pricing: message } }
  }
  if (/providerId/i.test(raw)) {
    return { ok: false, message, fields: { providerId: message } }
  }
  if (/modelId/i.test(raw)) {
    return { ok: false, message, fields: { modelId: message } }
  }
  if (/baseUrl/i.test(raw)) {
    return { ok: false, message, fields: { baseUrl: message } }
  }
  if (/tools/i.test(raw)) {
    return { ok: false, message, fields: { tools: message } }
  }
  if (/skills/i.test(raw)) {
    return { ok: false, message, fields: { skills: message } }
  }
  if (/name/i.test(raw) && !/variant/i.test(raw)) {
    return { ok: false, message, fields: { name: message } }
  }
  return { ok: false, message, fields: { general: message } }
}

/** 提交前本地校验。 */
export function validateResourceDraft(
  modal: ResourceModal,
  drafts: {
    providerDraft: ProviderDraft
    modelDraft: ModelDraft
    agentDraft: AgentDraft
  },
): ResourceFormValidationResult {
  try {
    if (modal.kind === 'provider') {
      if (!drafts.providerDraft.name.trim()) {
        return { ok: false, message: '请填写 Provider 名称', fields: { name: '请填写名称' } }
      }
      if (modal.mode === 'edit') {
        toEditableProviderUpdate(drafts.providerDraft)
      } else {
        toEditableProvider(drafts.providerDraft)
      }
      return { ok: true, message: '', fields: {} }
    }

    if (modal.kind === 'model') {
      // Reasoning 开启时，空思考强度用 Variant ID / medium 补全（用户常只改当前行）。
      const modelDraft: ModelDraft = {
        ...drafts.modelDraft,
        inputModalities: [...drafts.modelDraft.inputModalities],
        variants: drafts.modelDraft.variants.map((variant) => {
          const id = variant.id.trim()
          if (!drafts.modelDraft.reasoning) {
            return variant
          }
          const effort = variant.reasoningEffort.trim()
          if (effort) {
            return variant
          }
          return {
            ...variant,
            reasoningEffort: id || 'medium',
          }
        }),
      }
      const ids = modelDraft.variants.map((variant) => variant.id.trim()).filter(Boolean)
      if (ids.length === 0) {
        return {
          ok: false,
          message: '请至少添加一个 Variant',
          fields: { variants: '请添加 Variant' },
        }
      }
      if (!ids.includes(modelDraft.defaultVariant.trim())) {
        return {
          ok: false,
          message: '请选择一个有效的默认 Variant',
          fields: { defaultVariant: '请选择默认 Variant' },
        }
      }

      if (!modelDraft.name.trim()) {
        return { ok: false, message: '请填写 Model 名称', fields: { name: '请填写名称' } }
      }
      if (!modelDraft.providerId.trim()) {
        return { ok: false, message: '请选择 Provider', fields: { providerId: '请选择 Provider' } }
      }
      if (modelDraft.inputModalities.length === 0) {
        return {
          ok: false,
          message: '请至少选择一种输入类型（建议保留 TEXT）',
          fields: { inputModalities: '请至少选择一种输入类型' },
        }
      }
      // 写回 Reasoning effort 补全结果，供后续 submit 使用。
      drafts.modelDraft = modelDraft

      if (modal.mode === 'edit') {
        toEditableModelUpdate(modelDraft)
      } else {
        toEditableModel(modelDraft)
      }
      return { ok: true, message: '', fields: {} }
    }

    if (!drafts.agentDraft.name.trim()) {
      return { ok: false, message: '请填写 Agent 名称', fields: { name: '请填写名称' } }
    }
    if (!drafts.agentDraft.modelId.trim()) {
      return { ok: false, message: '请选择 Model', fields: { modelId: '请选择 Model' } }
    }
    if (modal.mode === 'edit') {
      toEditableAgentUpdate(drafts.agentDraft)
    } else {
      toEditableAgent(drafts.agentDraft)
    }
    return { ok: true, message: '', fields: {} }
  } catch (error) {
    return mapError(error)
  }
}
