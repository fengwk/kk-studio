import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import type {
  AgentDraft,
  ModelDraft,
  ModelGroupDraft,
  ProviderDraft,
  ResourceModal,
} from '@/features/catalog/ai-console-types'
import {
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelGroup,
  toEditableProvider,
  toEditableProviderUpdate,
} from '@/features/catalog/ai-resource-draft-codecs'
import { translate } from '@/shared/i18n'

export type ResourceFieldKey =
  | 'name'
  | 'modelId'
  | 'providerName'
  | 'baseUrl'
  | 'contextWindow'
  | 'inputLimit'
  | 'maxOutputTokens'
  | 'inputModalities'
  | 'variants'
  | 'defaultVariant'
  | 'pricing'
  | 'group'
  | 'fallbackStatusCodes'
  | 'cooldownSeconds'
  | 'model'
  | 'variant'
  | 'tools'
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

  if (/protocolOptions|原生协议选项/i.test(raw) || /protocolOptions|原生协议选项/.test(message)) {
    return { ok: false, message, fields: { variants: message } }
  }
  if (/reasoningEffort|思考强度/i.test(raw) || /思考强度/.test(message)) {
    return { ok: false, message, fields: { variants: message } }
  }
  if (/^variant must not be blank$/i.test(raw)) {
    return { ok: false, message, fields: { variant: message } }
  }
  if (/modelId/i.test(raw)) {
    return { ok: false, message, fields: { modelId: message } }
  }
  if (/variant|defaultVariant/i.test(raw)) {
    return { ok: false, message, fields: { variants: message, defaultVariant: message } }
  }
  // limit.input 的“must not exceed”必须先于 maxOutputTokens 的 exceed 规则命中，否则被吞掉。
  if (/limit\.input|inputLimit/i.test(raw)) {
    return { ok: false, message, fields: { inputLimit: message } }
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
  if (/group\.members|fallbackStatusCodes|cooldownSeconds/i.test(raw)) {
    return { ok: false, message, fields: { group: message } }
  }
  // 超时整数错误在 "non-negative" 上会命中下面的价格规则，必须先归到表单级提示。
  if (/canonical non-negative integer/i.test(raw)) {
    return { ok: false, message, fields: { general: message } }
  }
  if (/pricing|rate|condition|decimal|number|negative/i.test(raw)) {
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
  if (/tools/i.test(raw)) {
    return { ok: false, message, fields: { tools: message } }
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

/** 提交前本地校验：按资源种类分派到各自的校验体，统一经 `mapError` 翻译契约错误。 */
export function validateResourceDraft(
  modal: ResourceModal,
  drafts: {
    providerDraft: ProviderDraft
    modelDraft: ModelDraft
    modelGroupDraft: ModelGroupDraft
    agentDraft: AgentDraft
  },
): ResourceFormValidationResult {
  try {
    if (modal.kind === 'provider') {
      return validateProviderDraft(modal, drafts.providerDraft)
    }
    if (modal.kind === 'model') {
      if (modal.resourceKind === 'GROUP') {
        return validateModelGroupDraft(drafts.modelGroupDraft)
      }
      return validateModelDraft(drafts.modelDraft)
    }
    return validateAgentDraft(modal, drafts.agentDraft)
  } catch (error) {
    return mapError(error)
  }
}

/** 提供商：名称必填；转换器同时验证连接与凭据形状。 */
function validateProviderDraft(
  modal: ResourceModal,
  draft: ProviderDraft,
): ResourceFormValidationResult {
  if (!draft.name.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.providerName'),
      fields: { name: translate('ai.catalog.validation.name') },
    }
  }
  if (modal.mode === 'edit') {
    toEditableProviderUpdate(draft)
  } else {
    toEditableProvider(draft)
  }
  return { ok: true, message: '', fields: {} }
}

/** 模型组：名称必填且至少要有一个成员。 */
function validateModelGroupDraft(draft: ModelGroupDraft): ResourceFormValidationResult {
  if (!draft.name.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.modelName'),
      fields: { name: translate('ai.catalog.validation.name') },
    }
  }
  if (draft.members.length === 0) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.groupMembers'),
      fields: { group: translate('ai.catalog.validation.groupMembers') },
    }
  }
  toEditableModelGroup(draft)
  return { ok: true, message: '', fields: {} }
}

/**
 * 单个模型：先确认存在变体且默认变体属于其中之一，再校验名称、提供商与输入模态；
 * 顺序即用户修正顺序，不要合并。
 */
function validateModelDraft(draft: ModelDraft): ResourceFormValidationResult {
  const ids = draft.variants.map((variant) => variant.id.trim()).filter(Boolean)
  if (ids.length === 0) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.variantAddOne'),
      fields: { variants: translate('ai.catalog.validation.addVariant') },
    }
  }
  if (!ids.includes(draft.defaultVariant.trim())) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.defaultVariant'),
      fields: { defaultVariant: translate('ai.catalog.validation.defaultVariantField') },
    }
  }
  if (!draft.name.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.modelName'),
      fields: { name: translate('ai.catalog.validation.name') },
    }
  }
  if (!draft.providerName.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.provider'),
      fields: { providerName: translate('ai.catalog.validation.provider') },
    }
  }
  if (draft.inputModalities.length === 0) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.inputModality'),
      fields: { inputModalities: translate('ai.catalog.validation.inputModality') },
    }
  }
  toEditableModel(draft)
  return { ok: true, message: '', fields: {} }
}

/** Agent：名称必填；builtin driver（或未设 execution）必须有默认模型。 */
function validateAgentDraft(
  modal: ResourceModal,
  draft: AgentDraft,
): ResourceFormValidationResult {
  if (!draft.name.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.agentName'),
      fields: { name: translate('ai.catalog.validation.name') },
    }
  }
  if ((!draft.execution || draft.execution.driver === 'builtin') && !draft.model.trim()) {
    return {
      ok: false,
      message: translate('ai.catalog.validation.agentModel'),
      fields: { model: translate('ai.catalog.validation.agentModel') },
    }
  }
  if (modal.mode === 'edit') {
    toEditableAgentUpdate(draft)
  } else {
    toEditableAgent(draft)
  }
  return { ok: true, message: '', fields: {} }
}
