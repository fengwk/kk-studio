import type {
  ThreadComposerControlMenu,
  ThreadComposerModelOption,
  ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/ThreadComposerControls'
import type { TranslationKey } from '@/shared/i18n'

/** 表示「不绑定环境」的菜单项 id；环境项 id 由名称派生，避免与文件名冲突。 */
export const NO_ENVIRONMENT_ITEM_ID = 'environment:none'

/** 环境项的稳定 id：宽松名称可能包含冒号，因此用前缀而非裸名称。 */
export function environmentItemId(name: string): string {
  return `environment:name:${name}`
}

/** 菜单行：id 用于回传选择，label 已本地化。 */
export interface SelectableItem {
  id: string
  label: string
}

/**
 * 单 Model 的可选 Variant：组没有自己的 Variant 列表，去重后按配置顺序排列。
 * Variant 名称是模型事实，不做本地化或补默认值。
 */
export function variantIds(model: ThreadComposerModelOption): string[] {
  if (model.type !== 'MODEL') {
    return []
  }
  return Array.from(
    new Set((model.config?.variants ?? []).map((variant) => variant.id.trim()).filter(Boolean)),
  )
}

/** 环境菜单项：已配置环境去重保序，并补上当前绑定但不在列表里的名称。 */
export function environmentOptions(settings: ThreadComposerSettingsInput): string[] {
  const names = (settings.environments ?? []).map((env) => env.name.trim()).filter(Boolean)
  if (settings.environmentName && !names.includes(settings.environmentName)) {
    names.push(settings.environmentName)
  }
  return Array.from(new Set(names))
}

/**
 * 具备可选能力的模型：组恒可选，单 Model 至少有一个 Variant。
 * 既无 Variant 也不是组的模型不可选，不能出现在菜单里。
 */
export function usableModels(models: readonly ThreadComposerModelOption[]): ThreadComposerModelOption[] {
  return models.filter((model) => model.type === 'GROUP' || variantIds(model).length > 0)
}

/**
 * 当前菜单应显示的条目。
 *
 * - permission：Default / YOLO 两项固定；
 * - environment：不绑定 + 已配置环境；
 * - model：可模型列表按名称查询过滤（大小写不敏感）；
 * - variant：仅单 Model 的第二级列表。
 */
export function menuItems({
  menu,
  environmentNames,
  models,
  query,
  variantTarget,
  t,
}: {
  menu: ThreadComposerControlMenu
  environmentNames: string[]
  models: readonly ThreadComposerModelOption[]
  query: string
  variantTarget: ThreadComposerModelOption | null
  t: (key: TranslationKey) => string
}): SelectableItem[] {
  if (menu === 'permission') {
    return [
      { id: 'default', label: t('ai.runtime.composer.permissionDefault') },
      { id: 'yolo', label: t('ai.runtime.composer.permissionYolo') },
    ]
  }
  if (menu === 'environment') {
    return [
      { id: NO_ENVIRONMENT_ITEM_ID, label: t('ai.runtime.composer.environmentNone') },
      ...environmentNames.map((name) => ({ id: environmentItemId(name), label: name })),
    ]
  }
  if (menu === 'model') {
    const needle = query.trim().toLowerCase()
    return models.flatMap((model) => {
      if (needle && !model.name.toLowerCase().includes(needle)) {
        return []
      }
      return [{ id: model.name, label: model.name }]
    })
  }
  if (menu === 'variant' && variantTarget != null && variantTarget.type === 'MODEL') {
    return variantIds(variantTarget).map((variant) => ({ id: variant, label: variant }))
  }
  return []
}
