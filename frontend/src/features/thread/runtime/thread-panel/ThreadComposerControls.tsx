import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { formatModelLabel } from '@/features/catalog'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { ComposerControlMenuPanel } from '@/features/thread/runtime/thread-panel/ComposerControlMenuPanel'
import { ComposerControlSettingsButtons } from '@/features/thread/runtime/thread-panel/ComposerControlSettingsButtons'
import {
  useDismissOnPointerDownOutside,
  useMenuOpenEffect,
} from '@/features/thread/runtime/thread-panel/useComposerControlMenuLifecycle'
import type {
  ThreadComposerControlMenu,
  ThreadComposerModelOption,
  ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/thread-composer-control-types'
import {
  environmentItemId,
  environmentOptions,
  menuItems,
  NO_ENVIRONMENT_ITEM_ID,
  usableModels,
  variantIds,
  type SelectableItem,
} from '@/features/thread/runtime/thread-panel/composer-control-menus'
import './thread-composer-controls.css'

export type {
  ThreadComposerControlMenu,
  ThreadComposerModelOption,
  ThreadComposerModelSelection,
  ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/thread-composer-control-types'

/**
 * Composer 底栏的轻量 anchored menus。
 *
 * Permission 只有 Default/YOLO；Model 菜单直接使用全局唯一逻辑名。单 Model 存在多个
 * Variant 时进入第二级列表；组选择即完成，不提供组级 Variant 或伪造默认值。
 *
 * 本组件负责菜单的开合与选择分派；条目计算在 composer-control-menus.ts，listbox 的
 * 渲染在 ComposerControlMenuPanel 里。
 */
export function ThreadComposerControls({
  settings,
  menu,
  disabled,
  onMenuChange,
}: {
  settings: ThreadComposerSettingsInput
  menu: ThreadComposerControlMenu
  disabled: boolean
  onMenuChange: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
}) {
  const { t } = useI18n()
  const hostRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const [activeId, setActiveId] = useState('')
  const [query, setQuery] = useState('')
  const [variantModel, setVariantModel] = useState<ThreadComposerModelOption | null>(null)

  const models = settings.models
  const modelSelection = settings.modelSelection
  const environmentNames = useMemo(() => environmentOptions(settings), [settings])
  const selectableModels = useMemo(() => usableModels(models), [models])
  const currentModel = models.find((model) => model.name === modelSelection?.modelName)
  const variantTarget = variantModel ?? currentModel ?? null
  const items = useMemo<SelectableItem[]>(() => menuItems({
    menu,
    environmentNames,
    models: selectableModels,
    query,
    variantTarget,
    t,
  }), [environmentNames, menu, query, selectableModels, t, variantTarget])

  useMenuOpenEffect({
    menu,
    settings,
    currentModel,
    variantTarget,
    modelSelection,
    models,
    listRef,
    searchRef,
    setActiveId,
    setQuery,
    setVariantModel,
  })

  useDismissOnPointerDownOutside(hostRef, menu, onMenuChange)

  // 查询过滤后游标可能落在不存在的行：回到第一项，避免 Enter 命中隐藏项。
  useEffect(() => {
    if (menu !== 'model') {
      return
    }
    if (items.some((item) => item.id === activeId)) {
      return
    }
    setActiveId(items[0]?.id ?? '')
  }, [activeId, items, menu])

  useEffect(() => {
    const active = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [activeId])

  function select(id: string) {
    if (disabled) {
      return
    }
    if (menu === 'permission') {
      settings.onYoloChange(id === 'yolo')
      onMenuChange(null, true)
      return
    }
    if (menu === 'environment') {
      const selectedName = environmentNames.find((name) => environmentItemId(name) === id)
      if (id === NO_ENVIRONMENT_ITEM_ID) {
        settings.onEnvironmentChange(null)
      } else if (selectedName != null) {
        settings.onEnvironmentChange(selectedName)
      } else {
        return
      }
      onMenuChange(null, true)
      return
    }
    if (menu === 'model') {
      const selected = models.find((model) => model.name === id)
      if (selected == null) {
        return
      }
      if (selected.type === 'GROUP') {
        // 组选择即完成；成员 Variant 由组配置决定，绝不进入第二级或伪造 default。
        settings.onModelChange({ modelName: selected.name, variant: null })
        onMenuChange(null, true)
        return
      }
      const variants = variantIds(selected)
      if (variants.length <= 1) {
        settings.onModelChange({ modelName: selected.name, variant: variants[0] ?? null })
        onMenuChange(null, true)
        return
      }
      setVariantModel(selected)
      onMenuChange('variant')
      return
    }
    if (menu === 'variant' && variantTarget != null && variantIds(variantTarget).includes(id)) {
      settings.onModelChange({ modelName: variantTarget.name, variant: id })
      onMenuChange(null, true)
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onMenuChange(null, true)
      return
    }
    if (menu === 'variant' && (event.key === 'ArrowLeft' || event.key === 'Backspace')) {
      event.preventDefault()
      event.stopPropagation()
      setVariantModel(null)
      onMenuChange('model')
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      event.stopPropagation()
      setActiveId(event.key === 'Home' ? (items[0]?.id ?? '') : (items.at(-1)?.id ?? ''))
      return
    }
    if (event.key === 'Enter' && activeId) {
      event.preventDefault()
      event.stopPropagation()
      select(activeId)
    }
  }

  function moveActive(delta: number) {
    if (items.length === 0) {
      return
    }
    const currentIndex = Math.max(0, items.findIndex((item) => item.id === activeId))
    const nextIndex = (currentIndex + delta + items.length) % items.length
    setActiveId(items[nextIndex]?.id ?? '')
  }

  /** 当前生效项：permission/environment/model/variant 各自比对真实选择。 */
  function isCurrentItem(itemId: string): boolean {
    if (menu === 'permission') {
      return itemId === (settings.yoloEnabled ? 'yolo' : 'default')
    }
    if (menu === 'environment') {
      return (itemId === NO_ENVIRONMENT_ITEM_ID && settings.environmentName == null)
        || (settings.environmentName != null
          && itemId === environmentItemId(settings.environmentName))
    }
    if (menu === 'model') {
      return itemId === modelSelection?.modelName
    }
    return variantTarget?.name === modelSelection?.modelName
      && itemId === modelSelection?.variant
  }

  return (
    <div ref={hostRef} className="thread-composer-controls">
      <ComposerControlSettingsButtons
        settings={settings}
        menu={menu}
        disabled={disabled}
        modelEnabled={selectableModels.length > 0 && modelSelection != null}
        modelLabel={modelLabelOf(settings, t)}
        onMenuChange={onMenuChange}
      />
      {menu == null ? null : (
        <ComposerControlMenuPanel
          menu={menu}
          items={items}
          activeId={activeId}
          query={query}
          listLabel={menuListLabel(menu, t)}
          backLabel={menu === 'variant'
            ? (variantTarget == null ? t('ai.runtime.composer.model') : variantTarget.name)
            : null}
          search={menu === 'model'
            ? {
              label: t('ai.runtime.composer.modelSearch'),
              placeholder: t('ai.runtime.composer.modelSearchPlaceholder'),
            }
            : null}
          hint={menu === 'environment' ? settings.environmentSwitchNotice ?? null : null}
          currentOf={isCurrentItem}
          listRef={listRef}
          searchRef={searchRef}
          onQueryChange={setQuery}
          onActiveIdChange={setActiveId}
          onSelect={select}
          onBack={() => {
            setVariantModel(null)
            onMenuChange('model')
          }}
          onKeyDown={handleKeyDown}
        />
      )}
    </div>
  )
}

/** 三个触发按钮：Permission、Environment、Model/Variant。 */
function modelLabelOf(
  settings: ThreadComposerSettingsInput,
  t: (key: TranslationKey) => string,
): string {
  const selection = settings.modelSelection
  return selection?.modelName
    ? formatModelLabel(selection.modelName, selection.variant)
    : t('ai.runtime.composer.model')
}

function menuListLabel(
  menu: Exclude<ThreadComposerControlMenu, null>,
  t: (key: TranslationKey) => string,
): string {
  switch (menu) {
    case 'permission':
      return t('ai.runtime.composer.permissionOptions')
    case 'environment':
      return t('ai.runtime.composer.environmentOptions')
    case 'variant':
      return t('ai.runtime.composer.variantOptions')
    default:
      return t('ai.runtime.composer.modelOptions')
  }
}

/** 菜单打开时：复位二级状态、定位游标到当前选择，并把焦点交给搜索框或列表。 */
