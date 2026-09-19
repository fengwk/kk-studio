import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { Check, ChevronDown, ChevronLeft } from 'lucide-react'
import { useI18n } from '@/shared/i18n'

export interface ThreadComposerModelSelection {
  providerName: string
  modelName: string
  variant: string
}

export interface ThreadComposerModelOption {
  providerName: string
  name: string
  config: {
    defaultVariant: string
    variants: ReadonlyArray<{ id: string }>
  }
}

export interface ThreadComposerEnvironmentOption {
  name: string
}

export interface ThreadComposerSettingsInput {
  model: ThreadComposerModelSelection
  models: readonly ThreadComposerModelOption[]
  yoloEnabled: boolean
  environmentName: string | null
  environments: readonly ThreadComposerEnvironmentOption[]
  onModelChange: (model: ThreadComposerModelSelection) => void
  onYoloChange: (enabled: boolean) => void
  onEnvironmentChange: (environmentName: string | null) => void
}

export type ThreadComposerControlMenu = 'permission' | 'environment' | 'model' | 'variant' | null

interface SelectableItem {
  id: string
  label: string
}

const NO_ENVIRONMENT_ITEM_ID = 'environment:none'

/**
 * Composer 底栏的轻量 anchored menus。
 *
 * Permission 只有 Default/YOLO；Model 先选 provider/model，存在多个 Variant
 * 时再进入第二级列表。菜单使用 listbox/option 键盘语义，不创建 Modal/backdrop。
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
  const environmentOptions = useMemo(() => {
    const names = (settings.environments ?? []).map((env) => env.name.trim()).filter(Boolean)
    if (settings.environmentName && !names.includes(settings.environmentName)) {
      names.push(settings.environmentName)
    }
    return Array.from(new Set(names))
  }, [settings.environmentName, settings.environments])
  const models = useMemo(
    () => settings.models.filter((model) => variantIds(model).length > 0),
    [settings.models],
  )
  const currentModel = models.find((model) =>
    model.providerName === settings.model.providerName
    && model.name === settings.model.modelName,
  )
  const variantTarget = variantModel ?? currentModel ?? null
  const items = useMemo<SelectableItem[]>(() => {
    if (menu === 'permission') {
      return [
        { id: 'default', label: t('ai.runtime.composer.permissionDefault') },
        { id: 'yolo', label: t('ai.runtime.composer.permissionYolo') },
      ]
    }
    if (menu === 'environment') {
      return [
        { id: NO_ENVIRONMENT_ITEM_ID, label: t('ai.runtime.composer.environmentNone') },
        ...environmentOptions.map((name) => ({ id: environmentItemId(name), label: name })),
      ]
    }
    if (menu === 'model') {
      const needle = query.trim().toLowerCase()
      return models.flatMap((model) => {
        const label = `${model.providerName}/${model.name}`
        if (needle && !label.toLowerCase().includes(needle)) {
          return []
        }
        return [{ id: modelId(model), label }]
      })
    }
    if (menu === 'variant' && variantTarget != null) {
      return variantIds(variantTarget).map((variant) => ({ id: variant, label: variant }))
    }
    return []
  }, [environmentOptions, menu, models, query, t, variantTarget])

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
      const currentId = currentModel ? modelId(currentModel) : ''
      setActiveId(currentId)
    } else {
      const variants = variantTarget == null ? [] : variantIds(variantTarget)
      const selected =
        variantTarget?.providerName === settings.model.providerName
        && variantTarget.name === settings.model.modelName
        && variants.includes(settings.model.variant)
          ? settings.model.variant
          : variantTarget?.config.defaultVariant
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
    menu,
    models,
    settings.environmentName,
    settings.model.modelName,
    settings.model.providerName,
    settings.model.variant,
    settings.yoloEnabled,
    variantTarget,
  ])

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
  }, [menu, onMenuChange])

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
      const selectedName = environmentOptions.find((name) => environmentItemId(name) === id)
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
      const selected = models.find((model) => modelId(model) === id)
      if (selected == null) {
        return
      }
      const variants = variantIds(selected)
      if (variants.length === 1) {
        settings.onModelChange({
          providerName: selected.providerName,
          modelName: selected.name,
          variant: variants[0]!,
        })
        onMenuChange(null, true)
        return
      }
      setVariantModel(selected)
      onMenuChange('variant')
      return
    }
    if (menu === 'variant' && variantTarget != null && variantIds(variantTarget).includes(id)) {
      settings.onModelChange({
        providerName: variantTarget.providerName,
        modelName: variantTarget.name,
        variant: id,
      })
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

  const permissionLabel = settings.yoloEnabled
    ? t('ai.runtime.composer.permissionYolo')
    : t('ai.runtime.composer.permissionDefault')
  const environmentLabel = settings.environmentName ?? t('ai.runtime.composer.environmentNone')
  const modelLabel =
    settings.model.providerName && settings.model.modelName
      ? `${settings.model.providerName}/${settings.model.modelName} · ${settings.model.variant}`
      : t('ai.runtime.composer.model')

  return (
    <div ref={hostRef} className="thread-composer-controls">
      <div className="thread-composer-controls-left">
        <button
          type="button"
          className="thread-composer-control"
          aria-label={t('ai.runtime.composer.permission')}
          aria-haspopup="listbox"
          aria-expanded={menu === 'permission'}
          disabled={disabled}
          onClick={() => onMenuChange(menu === 'permission' ? null : 'permission')}
        >
          <span>{permissionLabel}</span>
          <ChevronDown aria-hidden="true" />
        </button>
        <button
          type="button"
          className="thread-composer-control thread-composer-environment-control"
          title={environmentLabel}
          aria-label={t('ai.runtime.composer.environment')}
          aria-haspopup="listbox"
          aria-expanded={menu === 'environment'}
          disabled={disabled}
          onClick={() => onMenuChange(menu === 'environment' ? null : 'environment')}
        >
          <span>{environmentLabel}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      </div>
      <div className="thread-composer-controls-right">
        <button
          type="button"
          className="thread-composer-control thread-composer-model-control"
          title={modelLabel}
          aria-label={t('ai.runtime.composer.modelVariant')}
          aria-haspopup="listbox"
          aria-expanded={menu === 'model' || menu === 'variant'}
          disabled={disabled || models.length === 0}
          onClick={() => onMenuChange(menu === 'model' || menu === 'variant' ? null : 'model')}
        >
          <span>{modelLabel}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      </div>
      {menu == null ? null : (
        <div
          className={[
            'thread-composer-menu',
            menu === 'permission'
              ? 'permission'
              : menu === 'environment'
                ? 'environment'
                : 'model',
          ].join(' ')}
        >
          {menu === 'variant' ? (
            <button
              type="button"
              className="thread-composer-menu-back"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                setVariantModel(null)
                onMenuChange('model')
              }}
            >
              <ChevronLeft aria-hidden="true" />
              {variantTarget == null
                ? t('ai.runtime.composer.model')
                : `${variantTarget.providerName}/${variantTarget.name}`}
            </button>
          ) : null}
          {menu === 'model' ? (
            <input
              ref={searchRef}
              type="search"
              className="thread-composer-menu-search"
              value={query}
              aria-label={t('ai.runtime.composer.modelSearch')}
              placeholder={t('ai.runtime.composer.modelSearchPlaceholder')}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleKeyDown}
            />
          ) : null}
          <ul
            ref={listRef}
            role="listbox"
            tabIndex={-1}
            aria-label={
              menu === 'permission'
                ? t('ai.runtime.composer.permissionOptions')
                : menu === 'environment'
                  ? t('ai.runtime.composer.environmentOptions')
                  : menu === 'variant'
                    ? t('ai.runtime.composer.variantOptions')
                    : t('ai.runtime.composer.modelOptions')
            }
            onKeyDown={handleKeyDown}
          >
            {items.map((item) => {
              const active = item.id === activeId
              const current =
                menu === 'permission'
                  ? item.id === (settings.yoloEnabled ? 'yolo' : 'default')
                  : menu === 'environment'
                    ? (item.id === NO_ENVIRONMENT_ITEM_ID && settings.environmentName == null)
                      || (
                        settings.environmentName != null
                        && item.id === environmentItemId(settings.environmentName)
                      )
                    : menu === 'model'
                      ? item.id === modelIdOfSelection(settings.model)
                      : (
                          variantTarget?.providerName === settings.model.providerName
                          && variantTarget.name === settings.model.modelName
                          && item.id === settings.model.variant
                        )
              return (
                <li key={item.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    aria-current={current ? 'true' : undefined}
                    className={active ? 'active' : undefined}
                    onMouseMove={() => setActiveId(item.id)}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => select(item.id)}
                  >
                    <span>{item.label}</span>
                    {current ? <Check aria-hidden="true" /> : null}
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}

function variantIds(model: ThreadComposerModelOption): string[] {
  return Array.from(
    new Set(model.config.variants.map((variant) => variant.id.trim()).filter(Boolean)),
  )
}

function modelId(model: ThreadComposerModelOption): string {
  return `${model.providerName}/${model.name}`
}

function modelIdOfSelection(model: ThreadComposerModelSelection): string {
  return `${model.providerName}/${model.modelName}`
}

function environmentItemId(name: string): string {
  return `environment:name:${name}`
}
