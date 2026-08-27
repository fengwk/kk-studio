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
import { translate } from '@/shared/i18n'

export type ResourceFieldKey =
  | 'name'
  | 'providerName'
  | 'baseUrl'
  | 'contextWindow'
  | 'maxOutputTokens'
  | 'inputModalities'
  | 'variants'
  | 'reasoningEffort'
  | 'defaultVariant'
  | 'pricing'
  | 'model'
  | 'variant'
  | 'toolIds'
  | 'skills'
  | 'subagents'
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
      fields: { reasoningEffort: translate('ai.catalog.form.reasoningEffortError'), variants: message },
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
  if (/providerName/i.test(raw)) {
    return { ok: false, message, fields: { providerName: message } }
  }
  if (/\bmodel\b/i.test(raw)) {
    return { ok: false, message, fields: { model: message } }
  }
  if (/baseUrl/i.test(raw)) {
    return { ok: false, message, fields: { baseUrl: message } }
  }
  if (/toolIds/i.test(raw)) {
    return { ok: false, message, fields: { toolIds: message } }
  }
  if (/skills/i.test(raw)) {
    return { ok: false, message, fields: { skills: message } }
  }
  // subagents 必须先于通用 name 匹配命中：duplicate 文案包含英文 "names"。
  if (/subagents/i.test(raw)) {
    return { ok: false, message, fields: { subagents: message } }
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
        return {
          ok: false,
          message: translate('ai.catalog.validation.providerName'),
          fields: { name: translate('ai.catalog.validation.name') },
        }
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
          message: translate('ai.catalog.validation.variantAddOne'),
          fields: { variants: translate('ai.catalog.validation.addVariant') },
        }
      }
      if (!ids.includes(modelDraft.defaultVariant.trim())) {
        return {
          ok: false,
          message: translate('ai.catalog.validation.defaultVariant'),
          fields: { defaultVariant: translate('ai.catalog.validation.defaultVariantField') },
        }
      }

      if (!modelDraft.name.trim()) {
        return {
          ok: false,
          message: translate('ai.catalog.validation.modelName'),
          fields: { name: translate('ai.catalog.validation.name') },
        }
      }
      if (!modelDraft.providerName.trim()) {
        return {
          ok: false,
          message: translate('ai.catalog.validation.provider'),
          fields: { providerName: translate('ai.catalog.validation.provider') },
        }
      }
      if (modelDraft.inputModalities.length === 0) {
        return {
          ok: false,
          message: translate('ai.catalog.validation.inputModality'),
          fields: { inputModalities: translate('ai.catalog.validation.inputModality') },
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
      return {
        ok: false,
        message: translate('ai.catalog.validation.agentName'),
        fields: { name: translate('ai.catalog.validation.name') },
      }
    }
    if (!drafts.agentDraft.model.trim()) {
      return {
        ok: false,
        message: translate('ai.catalog.validation.agentModel'),
        fields: { model: translate('ai.catalog.validation.agentModel') },
      }
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
