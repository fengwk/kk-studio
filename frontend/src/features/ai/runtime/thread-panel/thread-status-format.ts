import { translate } from '@/shared/i18n'

/**
 * Environment binding 的只读形状（{name, workspacePath}）。
 *
 * thread-panel 是可移植包，只允许依赖 thread-timeline-types 与本地模块（no-restricted-imports），
 * 因此不直接引用 API contract 类型；调用方传入的结构与该形状结构兼容。
 */
export interface EnvironmentBindingShape {
  name: string
  workspacePath: string
}

export interface ThreadStatusSegment {
  key: 'agent' | 'model' | 'environment' | 'notifications'
  className: string
  text: string
  title: string
  onClick?: () => void
  onSecondaryClick?: () => void
}

export interface ThreadStatusModel {
  agentLabel: string
  provider: string
  model: string
  variant: string
  environment: string
  environmentWorkspacePath: string
  yoloOn: boolean
  agentText: string
  modelText: string
  environmentText: string
  segments: ThreadStatusSegment[]
}

export interface ThreadStatusModelInput {
  agentName?: string
  providerName?: string
  modelName?: string
  variantName?: string
  /** 完整 Environment binding（可 null）：null => `env:none`；非 null 时展示 name + workspacePath。 */
  environment?: EnvironmentBindingShape | null
  /** 该 binding name 的实时可用标记（统一可用性规则）；false/未知 => `env:<name> · ws:<path> (unavailable)`。 */
  environmentReady?: boolean
  yoloEnabled?: boolean
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
  onEnvironmentClick?: () => void
  notificationsEnabled?: boolean
  notificationPermission?: 'default' | 'denied' | 'granted' | 'unsupported'
  onNotificationsToggle?: () => void
}

/** 规范化空白占位字符串；对 null/undefined/'undefined'/'null'/'-' 返回 ""。 */
function clean(value?: string | null): string {
  const text = (value ?? '').trim()
  if (!text || text === '-' || text === 'undefined' || text === 'null') {
    return ''
  }
  return text
}

/** 由面板输入构建稳定的纯文本状态模型，不涉及布局与 DOM。 */
export function buildThreadStatusModel(input: ThreadStatusModelInput): ThreadStatusModel {
  const agentLabel = clean(input.agentName) || translate('ai.runtime.status.agentFallback')
  const provider = clean(input.providerName)
  const model = clean(input.modelName) || translate('ai.runtime.status.modelFallback')
  const variant = clean(input.variantName) || translate('ai.runtime.status.variantFallback')
  const binding = input.environment
  const environmentName = binding ? clean(binding.name) : ''
  const environmentWorkspacePath = binding ? clean(binding.workspacePath) : ''
  const yoloOn = Boolean(input.yoloEnabled)

  // 调用方传入的 modelName 可能已是规范的 provider/model 引用。
  const composedModelRef =
    provider && model && !model.startsWith(`${provider}/`) ? `${provider}/${model}` : model
  const modelText = translate('ai.runtime.status.modelText', {
    model: composedModelRef,
    variant,
  })
  const agentText = yoloOn
    ? translate('ai.runtime.status.agentYoloText', { name: agentLabel })
    : translate('ai.runtime.status.agentText', { name: agentLabel })
  const environmentText =
    environmentName === ''
      ? translate('ai.runtime.status.environmentNoneText')
      : input.environmentReady === false
        ? translate('ai.runtime.status.environmentUnavailableText', {
          name: environmentName,
          workspace: environmentWorkspacePath,
        })
        : translate('ai.runtime.status.environmentText', {
          name: environmentName,
          workspace: environmentWorkspacePath,
        })

  const segments: ThreadStatusSegment[] = [
    {
      key: 'agent',
      className: 'thread-status-agent',
      text: agentText,
      title: input.onAgentClick
        ? `${agentText} · ${translate('ai.runtime.status.agentSwitchTitle')}`
        : agentText,
      onClick: input.onAgentClick,
    },
    {
      key: 'model',
      className: 'thread-status-model',
      text: modelText,
      title:
        input.onModelClick || input.onVariantClick
          ? `${modelText} · ${translate('ai.runtime.status.modelSwitchTitle')}`
          : modelText,
      onClick: input.onModelClick,
      onSecondaryClick: input.onVariantClick,
    },
    {
      key: 'environment',
      className: 'thread-status-environment',
      text: environmentText,
      title: input.onEnvironmentClick
        ? `${environmentText} · ${translate('ai.runtime.status.environmentSwitchTitle')}`
        : environmentText,
      onClick: input.onEnvironmentClick,
    },
  ]
  if (input.onNotificationsToggle || input.notificationsEnabled != null) {
    const permission = input.notificationPermission ?? 'default'
    const text =
      permission === 'unsupported'
        ? translate('ai.runtime.status.notificationsUnsupported')
        : permission === 'denied'
          ? translate('ai.runtime.status.notificationsDenied')
          : input.notificationsEnabled
            ? translate('ai.runtime.status.notificationsOn')
            : translate('ai.runtime.status.notificationsOff')
    segments.push({
      key: 'notifications',
      className: 'thread-status-notification-toggle',
      text,
      title: translate('ai.runtime.status.notificationsToggleTitle'),
      onClick: input.onNotificationsToggle,
    })
  }

  return {
    agentLabel,
    provider,
    model,
    variant,
    environment: environmentName,
    environmentWorkspacePath,
    yoloOn,
    agentText,
    modelText,
    environmentText,
    segments,
  }
}
