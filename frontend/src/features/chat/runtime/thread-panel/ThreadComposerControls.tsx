import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { Check, ChevronDown, ChevronLeft } from 'lucide-react'
import { formatModelLabel } from '@/features/catalog'
import type { AgentModelKind } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'

export interface ThreadComposerModelSelection {
  /** 全局唯一逻辑模型名（单 Model 或组）。 */
  modelName: string
  /** 单 Model 的可选 Variant；组为空。 */
  variant?: string | null
}

export interface ThreadComposerModelOption {
  name: string
  kind: AgentModelKind
  config?: {
    defaultVariant?: string | null
    variants?: ReadonlyArray<{ id: string }>
  } | null
}

export interface ThreadComposerEnvironmentOption {
  name: string
}

/**
 * 设置选择的状态：draft=本地草稿，将随下一条消息一起生效；pending=已提交，等待安全点生效；
 * null=无需要展示的差异（已生效或未编辑）。applied 不做常驻提示。
 */
export type ThreadComposerSettingsStatus = 'draft' | 'pending' | null

export interface ThreadComposerSettingsInput {
  model: ThreadComposerModelSelection
  models: readonly ThreadComposerModelOption[]
  yoloEnabled: boolean
  environmentName: string | null
  environments: readonly ThreadComposerEnvironmentOption[]
  status?: ThreadComposerSettingsStatus
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
 * Permission 只有 Default/YOLO；Model 菜单直接使用全局唯一逻辑名。单 Model 存在多个
 * Variant 时进入第二级列表；组选择即完成，不提供组级 Variant 或伪造默认值。
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
  const models = settings.models
  const usableModels = useMemo(
    () => models.filter((model) => model.kind === 'GROUP' || variantIds(model).length > 0),
    [models],
  )
  const currentModel = models.find((model) => model.name === settings.model.modelName)
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
      return usableModels.flatMap((model) => {
        if (needle && !model.name.toLowerCase().includes(needle)) {
          return []
        }
        return [{ id: model.name, label: model.name }]
      })
    }
    if (menu === 'variant' && variantTarget != null && variantTarget.kind === 'MODEL') {
      return variantIds(variantTarget).map((variant) => ({ id: variant, label: variant }))
    }
    return []
  }, [environmentOptions, menu, usableModels, query, t, variantTarget])

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
        variantTarget?.name === settings.model.modelName
          && variants.includes(settings.model.variant ?? '')
          ? settings.model.variant ?? ''
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
    menu,
    models,
    settings.environmentName,
    settings.model.modelName,
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
      const selected = models.find((model) => model.name === id)
      if (selected == null) {
        return
      }
      if (selected.kind === 'GROUP') {
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

  const permissionLabel = settings.yoloEnabled
    ? t('ai.runtime.composer.permissionYolo')
    : t('ai.runtime.composer.permissionDefault')
  const environmentLabel = settings.environmentName ?? t('ai.runtime.composer.environmentNone')
  const modelLabel = settings.model.modelName
    ? formatModelLabel(settings.model.modelName, settings.model.variant)
    : t('ai.runtime.composer.model')
  const settingsStatus = settings.status ?? null

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
        {settingsStatus === 'pending' ? (
          <span
            className="thread-composer-settings-status"
            data-settings-status="pending"
            title={t('ai.runtime.composer.settingsPendingTitle')}
          >
            {t('ai.runtime.composer.settingsPendingLabel')}
          </span>
        ) : null}
      </div>
      <div className="thread-composer-controls-right">
        <button
          type="button"
          className="thread-composer-control thread-composer-model-control"
          title={modelLabel}
          aria-label={t('ai.runtime.composer.modelVariant')}
          aria-haspopup="listbox"
          aria-expanded={menu === 'model' || menu === 'variant'}
          disabled={disabled || usableModels.length === 0}
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
                : variantTarget.name}
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
                      ? item.id === settings.model.modelName
                      : (
                          variantTarget?.name === settings.model.modelName
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
  if (model.kind !== 'MODEL') {
    return []
  }
  return Array.from(
    new Set((model.config?.variants ?? []).map((variant) => variant.id.trim()).filter(Boolean)),
  )
}

function environmentItemId(name: string): string {
  return `environment:name:${name}`
}
