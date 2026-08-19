import { useI18n } from '@/shared/i18n'
import {
  RestartNotice,
  SettingsCard,
  SettingsNumberField,
  SettingsSelectField,
  SettingsSwitchRow,
} from '@/features/settings/settings-primitives'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsAiRuntimeDraft } from '@/features/settings/system-settings-draft'

/** aiRuntime section：共享调用重试、自动压缩与 subagent 预算（进程级，重启后生效）。 */
export function AiRuntimeTab({ value, onChange }: SectionEditorProps<SystemSettingsAiRuntimeDraft>) {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack">
      <RestartNotice />

      <SettingsCard
        title={t('settings.section.aiRuntime.retry.title')}
        description="settings.section.aiRuntime.retry.description"
      >
        <SettingsNumberField
          label={t('settings.field.aiRuntime.retryMaxRetries')}
          value={value.retryMaxRetries}
          min={0}
          onChange={(next) => onChange({ ...value, retryMaxRetries: next })}
        />
        <SettingsSelectField
          label={t('settings.field.aiRuntime.retryBackoffStrategy')}
          value={value.retryBackoffStrategy}
          onChange={(next) =>
            onChange({ ...value, retryBackoffStrategy: next as SystemSettingsAiRuntimeDraft['retryBackoffStrategy'] })
          }
          options={[
            { value: 'FIXED', label: t('settings.option.retryBackoff.fixed') },
            { value: 'EXPONENTIAL', label: t('settings.option.retryBackoff.exponential') },
          ]}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.retryBaseDelayMillis')}
          value={value.retryBaseDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, retryBaseDelayMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.retryMaxDelayMillis')}
          value={value.retryMaxDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, retryMaxDelayMillis: next })}
        />
      </SettingsCard>

      <SettingsCard
        title={t('settings.section.aiRuntime.compaction.title')}
        description="settings.section.aiRuntime.compaction.description"
      >
        <SettingsSwitchRow
          label={t('settings.field.aiRuntime.compactionEnabled')}
          checked={value.compactionEnabled}
          onChange={(next) => onChange({ ...value, compactionEnabled: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.compactionReserveTokens')}
          value={value.compactionReserveTokens}
          min={1}
          onChange={(next) => onChange({ ...value, compactionReserveTokens: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.compactionMaxRecentTokens')}
          value={value.compactionMaxRecentTokens}
          min={1}
          onChange={(next) => onChange({ ...value, compactionMaxRecentTokens: next })}
        />
      </SettingsCard>

      <SettingsCard
        title={t('settings.section.aiRuntime.subagent.title')}
        description="settings.section.aiRuntime.subagent.description"
      >
        <SettingsNumberField
          label={t('settings.field.aiRuntime.subagentMaxDepth')}
          value={value.subagentMaxDepth}
          min={1}
          onChange={(next) => onChange({ ...value, subagentMaxDepth: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.subagentMaxConcurrency')}
          value={value.subagentMaxConcurrency}
          min={1}
          onChange={(next) => onChange({ ...value, subagentMaxConcurrency: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.subagentMaxTotalConcurrency')}
          value={value.subagentMaxTotalConcurrency}
          min={1}
          hint="settings.field.aiRuntime.subagentMaxTotalConcurrency.hint"
          onChange={(next) => onChange({ ...value, subagentMaxTotalConcurrency: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.subagentIdleTimeoutMillis')}
          value={value.subagentIdleTimeoutMillis}
          min={0}
          hint="settings.field.aiRuntime.subagentIdleTimeoutMillis.hint"
          onChange={(next) => onChange({ ...value, subagentIdleTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.aiRuntime.subagentMaxTurns')}
          value={value.subagentMaxTurns}
          min={1}
          onChange={(next) => onChange({ ...value, subagentMaxTurns: next })}
        />
      </SettingsCard>
    </div>
  )
}
