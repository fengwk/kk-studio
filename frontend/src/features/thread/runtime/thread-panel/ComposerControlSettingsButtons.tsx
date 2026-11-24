import { ChevronDown } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type {
  ThreadComposerControlMenu,
  ThreadComposerSettingsInput,
} from '@/features/thread/runtime/thread-panel/thread-composer-control-types'

export function ComposerControlSettingsButtons({
  settings,
  menu,
  disabled,
  modelEnabled,
  modelLabel,
  onMenuChange,
}: {
  settings: ThreadComposerSettingsInput
  menu: ThreadComposerControlMenu
  disabled: boolean
  modelEnabled: boolean
  modelLabel: string
  onMenuChange: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
}) {
  const { t } = useI18n()
  const permissionLabel = settings.yoloEnabled
    ? t('ai.runtime.composer.permissionYolo')
    : t('ai.runtime.composer.permissionDefault')
  const environmentLabel = settings.environmentName ?? t('ai.runtime.composer.environmentNone')
  const settingsStatus = settings.status ?? null
  return (
    <>
      <div className="thread-composer-controls-left">
        <button
          type="button"
          className="thread-composer-control thread-composer-permission-control"
          data-ui-focus="control"
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
          data-ui-focus="control"
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
          data-ui-focus="control"
          title={modelLabel}
          aria-label={t('ai.runtime.composer.modelVariant')}
          aria-haspopup="listbox"
          aria-expanded={menu === 'model' || menu === 'variant'}
          disabled={disabled || !modelEnabled}
          onClick={() => onMenuChange(menu === 'model' || menu === 'variant' ? null : 'model')}
        >
          <span>{modelLabel}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      </div>
    </>
  )
}
