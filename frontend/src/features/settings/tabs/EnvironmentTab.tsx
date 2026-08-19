import { useI18n } from '@/shared/i18n'
import { RestartNotice, SettingsCard, SettingsNumberField } from '@/features/settings/settings-primitives'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsEnvironmentDraft } from '@/features/settings/system-settings-draft'

/** environment section：daemon gateway 的资源/消息边界与超时（启动快照，重启后生效）。 */
export function EnvironmentTab({
  value,
  onChange,
}: SectionEditorProps<SystemSettingsEnvironmentDraft>) {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack">
      <RestartNotice />
      <SettingsCard
        title={t('settings.section.environment.title')}
        description="settings.section.environment.description"
      >
        <SettingsNumberField
          label={t('settings.field.environment.maxResourceBytes')}
          value={value.maxResourceBytes}
          min={1}
          onChange={(next) => onChange({ ...value, maxResourceBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.environment.maxMessageBytes')}
          value={value.maxMessageBytes}
          min={1}
          onChange={(next) => onChange({ ...value, maxMessageBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.environment.heartbeatTimeoutMillis')}
          value={value.heartbeatTimeoutMillis}
          min={1}
          onChange={(next) => onChange({ ...value, heartbeatTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.environment.directoryListTimeoutMillis')}
          value={value.directoryListTimeoutMillis}
          min={1}
          onChange={(next) => onChange({ ...value, directoryListTimeoutMillis: next })}
        />
      </SettingsCard>
    </div>
  )
}
