import { useI18n } from '@/shared/i18n'
import type { SystemSettingsEditor } from '@/features/settings/useSystemSettingsEditor'
import type { DraftValidationReason } from '@/features/settings/system-settings-draft'

const DRAFT_ERROR_KEYS: Record<DraftValidationReason, string> = {
  blankToolName: 'settings.error.permissionToolNameRequired',
  duplicateToolName: 'settings.error.permissionDuplicateToolName',
  blankPattern: 'settings.error.permissionPatternRequired',
  emptyNumericField: 'settings.error.numericFieldRequired',
}

/**
 * 聚合编辑工具条：loading/error/dirty/saving 状态与 Save/Reset。
 * 只有 server tab 需要展示（draft 来自 server 权威聚合）。
 */
export function SettingsToolbar({ editor }: { editor: SystemSettingsEditor }) {
  const { t } = useI18n()
  const draftError = editor.draftError ? t(DRAFT_ERROR_KEYS[editor.draftError]) : null
  const banner = draftError ?? editor.saveError

  return (
    <div className="settings-toolbar" aria-label={t('settings.toolbar.ariaLabel')}>
      {banner ? (
        <div
          className="settings-error-banner"
          role="alert"
          data-kind="error"
        >
          {banner}
        </div>
      ) : null}
      <div className="settings-toolbar-actions">
        <span className="settings-dirty" data-dirty={editor.dirty}>
          {editor.dirty ? t('settings.unsavedChanges') : t('settings.allSaved')}
        </span>
        <button
          type="button"
          className="settings-button primary"
          disabled={!editor.dirty || editor.saving}
          onClick={() => {
            void editor.save()
          }}
        >
          {editor.saving ? t('settings.saving') : t('settings.save')}
        </button>
        <button
          type="button"
          className="settings-button"
          disabled={!editor.dirty || editor.saving}
          onClick={editor.reset}
        >
          {t('settings.reset')}
        </button>
      </div>
    </div>
  )
}
