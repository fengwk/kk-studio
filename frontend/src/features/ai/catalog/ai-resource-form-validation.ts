import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
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
