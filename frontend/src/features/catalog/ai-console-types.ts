import type { AgentProviderType } from '@/shared/api/contracts/ai-catalog'
import type { CatalogVersion } from '@/shared/api/contracts/base'
import type {
  AgentDefinitionType,
  AgentModelInputModality,
  AgentModelKind,
  AgentModelPricingDTO,
  SkillRefDTO,
} from '@/shared/api/generated/contracts'

export interface ProviderDraft {
  name: string
  description: string
  providerType: AgentProviderType
  baseUrl: string
  credential: string
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
  modelHttpRetryStatusCodes: (number | string)[] | null
}

/**
 * 一个 Agent model 变体。表单输入使用字符串类型，以便空字段能干净地映射到 wire {@link AgentModelVariantDTO}
 * 上的 {@code null}/{@code undefined}。
 */
export interface VariantDraft {
  /** 仅客户端使用的 React key。 */
  draftId: string
  id: string
  /** 厂商定义的思考强度；off 表示显式关闭，空串表示协议默认。 */
  reasoningEffort: string
  /** 厂商原生协议请求选项（JSON 文本）。 */
  protocolOptionsJson: string
}

/**
 * 单 Model 的完整价格草稿。所有十进制值保持字符串，提交时原样发送；
 * 覆盖/倍率规则与结构化条件也直接使用 wire 形状，确保完整原子替换。
 */
export type ModelPricingDraft = AgentModelPricingDTO

/** 单 Model 的表单草稿。输入保持字符串类型以获得可预测的编辑体验。 */
export interface ModelDraft {
  /** 全局唯一逻辑名。 */
  name: string
  /** 发往上游 Provider 的真实模型标识；可编辑且必填。 */
  modelId: string
  description: string
  providerName: string
  contextWindow: string
  maxOutputTokens: string
  tools: boolean
  reasoning: boolean
  /**
   * 提交时非空。新建 model 默认始终包含 {@code TEXT}；切换时通过不可变数组新增/移除项（不进行原地变更）。
   */
  inputModalities: AgentModelInputModality[]
  defaultVariant: string
  variants: VariantDraft[]
  pricing: ModelPricingDraft
}

/** 组的成员草稿；顺序即 Fill First 顺序。 */
export interface ModelGroupMemberDraft {
  draftId: string
  modelName: string
  variant: string
}

/** Model Group 表单草稿；不含 Provider、价格、Variant 或能力覆写字段。 */
export interface ModelGroupDraft {
  name: string
  description: string
  members: ModelGroupMemberDraft[]
  fallbackStatusCodes: (number | string)[]
  cooldownSeconds: string
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  /** 全局唯一逻辑模型名（单 Model 或组）。 */
  model: string
  /** 单 Model 的可选覆盖；组为空。 */
  variant: string
  inheritParentEnvironment: boolean
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
}

export type ResourceModal =
  | { kind: 'provider'; mode: 'create' }
  | { kind: 'provider'; mode: 'edit'; name: string; expectedVersion: CatalogVersion }
  | { kind: 'model'; mode: 'create'; resourceKind: AgentModelKind }
  | {
      kind: 'model'
      mode: 'edit'
      resourceKind: AgentModelKind
      name: string
      expectedVersion: CatalogVersion
    }
  | { kind: 'agent'; mode: 'create'; type: AgentDefinitionType }
  | {
      kind: 'agent'
      mode: 'edit'
      name: string
      /** 系统持有的 identity 类型；前端据此保护删除等动作，不随请求发送。 */
      type: AgentDefinitionType
      model: string | null
      expectedVersion: CatalogVersion
    }

