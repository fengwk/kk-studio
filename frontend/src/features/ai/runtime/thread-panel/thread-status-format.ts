import { translate } from '@/shared/i18n'

export interface ThreadStatusSegment {
  key: 'agent' | 'model' | 'environment'
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
  environmentDisplayName?: string | null
  yoloEnabled?: boolean
  onAgentClick?: () => void
  onModelClick?: () => void
  onVariantClick?: () => void
  onEnvironmentClick?: () => void
}

/** Normalize blank placeholder strings; returns "" for null/undefined/'undefined'/'null'/'-'. */
function clean(value?: string | null): string {
  const text = (value ?? '').trim()
  if (!text || text === '-' || text === 'undefined' || text === 'null') {
    return ''
  }
  return text
}





/** Build the stable text-only status model from panel inputs. No layout, no DOM. */
export function buildThreadStatusModel(input: ThreadStatusModelInput): ThreadStatusModel {
  const agentLabel = clean(input.agentName) || translate('ai.runtime.status.agentFallback')
  const provider = clean(input.providerName)
  const model = clean(input.modelName) || translate('ai.runtime.status.modelFallback')
  const variant = clean(input.variantName) || translate('ai.runtime.status.variantFallback')
  const environment =
    clean(input.environmentDisplayName) || translate('ai.runtime.status.environmentFallback')
  const yoloOn = Boolean(input.yoloEnabled)

  // modelName may already be the canonical provider/model ref from callers.
  const composedModelRef =
    provider && model && !model.startsWith(`${provider}/`) ? `${provider}/${model}` : model
  const modelText = translate('ai.runtime.status.modelText', {
    model: composedModelRef,
    variant,
  })
  const agentText = yoloOn
    ? translate('ai.runtime.status.agentYoloText', { name: agentLabel })
    : translate('ai.runtime.status.agentText', { name: agentLabel })
  const environmentText = translate('ai.runtime.status.environmentText', { name: environment })

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

  return {
    agentLabel,
    provider,
    model,
    variant,
    environment,
    yoloOn,
    agentText,
    modelText,
    environmentText,
    segments,
  }
}
