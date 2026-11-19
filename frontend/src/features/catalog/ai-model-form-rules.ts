import type { ModelDraft, VariantDraft } from '@/features/catalog/ai-console-types'
import { variantOptionsFromDraft } from '@/features/catalog/ai-draft-normalizers'
import type { ModelInputModality, ProviderDTO } from '@/shared/api/generated/contracts'

export interface ProviderOption {
  value: string
  label: string
  disabled?: boolean
}

/**
 * 编辑一个已下线 Provider 的模型时，Provider 字段仍要显示当前值：
 * 把它作为不可选的一项前置，避免下拉里出现空值。
 */
export function providerOptionsFor(
  providers: ProviderDTO[],
  draft: ModelDraft,
  mode: 'create' | 'edit',
  unavailableLabel: string,
): { options: ProviderOption[]; unavailable: boolean } {
  const unavailable =
    mode === 'edit' && Boolean(draft.providerName) && !providers.some((p) => p.name === draft.providerName)
  const options: ProviderOption[] = providers.map((provider) => ({
    value: provider.name,
    label: provider.name,
  }))
  if (!unavailable) {
    return { options, unavailable }
  }
  return {
    options: [
      { value: draft.providerName, label: `${draft.providerName} · ${unavailableLabel}`, disabled: true },
      ...options,
    ],
    unavailable,
  }
}

/** 变体变化后重新选出默认变体：优先保留调用方偏好，否则沿用原值，再否则取第一项。 */
export function variantSelection(
  draft: ModelDraft,
  nextVariants: VariantDraft[],
  preferredDefaultVariant?: string,
): Pick<ModelDraft, 'variants' | 'defaultVariant'> {
  const nextOptions = variantOptionsFromDraft(nextVariants)
  const preferred = preferredDefaultVariant?.trim() || draft.defaultVariant.trim()
  return {
    variants: nextVariants,
    defaultVariant:
      preferred && nextOptions.includes(preferred) ? preferred : (nextOptions[0] ?? ''),
  }
}

/** 至少保留一种输入类型；禁止全部取消勾选。 */
export function toggleInputModality(
  current: ModelInputModality[],
  value: ModelInputModality,
): ModelInputModality[] {
  const next = current.includes(value)
    ? current.filter((item) => item !== value)
    : [...current, value]
  return next.length > 0 ? next : ['TEXT']
}

/** 默认变体下拉当前应显示的项：草稿值不在候选项内时回落到第一项。 */
export function selectedDefaultVariant(draft: ModelDraft): string {
  const options = variantOptionsFromDraft(draft.variants)
  const trimmed = draft.defaultVariant.trim()
  return options.includes(trimmed) ? trimmed : (options[0] ?? '')
}
