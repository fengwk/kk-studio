import { useEffect } from 'react'
import {
  environmentItemId,
  NO_ENVIRONMENT_ITEM_ID,
  variantIds,
} from '@/features/thread/runtime/thread-panel/composer-control-menus'
import type {
  ThreadComposerControlMenu,
  ThreadComposerModelOption,
  ThreadComposerModelSelection,
  ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/thread-composer-control-types'

export function useMenuOpenEffect({
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
}: {
  menu: ThreadComposerControlMenu
  settings: ThreadComposerSettingsInput
  currentModel: ThreadComposerModelOption | undefined
  variantTarget: ThreadComposerModelOption | null
  modelSelection: ThreadComposerModelSelection | null
  models: readonly ThreadComposerModelOption[]
  listRef: React.RefObject<HTMLUListElement | null>
  searchRef: React.RefObject<HTMLInputElement | null>
  setActiveId: (id: string) => void
  setQuery: (query: string) => void
  setVariantModel: (model: ThreadComposerModelOption | null) => void
}) {
  useEffect(() => {
    if (menu == null) {
      setVariantModel(null)
      setQuery('')
      return
    }
    if (menu === 'permission') {
      setActiveId(settings.yoloEnabled ? 'yolo' : 'default')
    } else if (menu === 'environment') {
      setActiveId(
        settings.environmentName == null
          ? NO_ENVIRONMENT_ITEM_ID
          : environmentItemId(settings.environmentName),
      )
    } else if (menu === 'model') {
      setActiveId(currentModel?.name ?? '')
    } else {
      const variants = variantTarget == null ? [] : variantIds(variantTarget)
      const selected =
        variantTarget?.name === modelSelection?.modelName
          && variants.includes(modelSelection?.variant ?? '')
          ? modelSelection?.variant ?? ''
          : variantTarget?.config?.defaultVariant
      setActiveId(selected && variants.includes(selected) ? selected : (variants[0] ?? ''))
    }
    window.setTimeout(() => {
      if (menu === 'model') {
        searchRef.current?.focus({ preventScroll: true })
        return
      }
      listRef.current?.focus({ preventScroll: true })
    }, 0)
  }, [
    currentModel,
    listRef,
    menu,
    modelSelection?.modelName,
    modelSelection?.variant,
    models,
    searchRef,
    setActiveId,
    setQuery,
    setVariantModel,
    settings.environmentName,
    settings.yoloEnabled,
    variantTarget,
  ])
}

/** 菜单打开时点击 host 之外即关闭。 */
export function useDismissOnPointerDownOutside(
  hostRef: React.RefObject<HTMLDivElement | null>,
  menu: ThreadComposerControlMenu,
  onMenuChange: (menu: ThreadComposerControlMenu) => void,
) {
  useEffect(() => {
    if (menu == null) {
      return
    }
    const handlePointerDown = (event: MouseEvent) => {
      if (!hostRef.current?.contains(event.target as Node)) {
        onMenuChange(null)
      }
    }
    document.addEventListener('mousedown', handlePointerDown)
    return () => document.removeEventListener('mousedown', handlePointerDown)
  }, [hostRef, menu, onMenuChange])
}
