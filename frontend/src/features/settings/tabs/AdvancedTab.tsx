import { useI18n } from '@/shared/i18n'
import { RestartNotice, SettingsCard, SettingsNumberField } from '@/features/settings/settings-primitives'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsAdvancedDraft } from '@/features/settings/system-settings-draft'

/** advanced section：processor/dispatcher/executor/事件通道/工作通知的进程级运行预算（重启后生效）。 */
export function AdvancedTab({ value, onChange }: SectionEditorProps<SystemSettingsAdvancedDraft>) {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack">
      <RestartNotice />

      <SettingsCard title={t('settings.section.advanced.resource.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.resourceMaxBytes')}
          value={value.resourceMaxBytes}
          min={1}
          onChange={(next) => onChange({ ...value, resourceMaxBytes: next })}
        />
      </SettingsCard>

      <SettingsCard title={t('settings.section.advanced.processor.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.processorLeaseDurationMillis')}
          value={value.processorLeaseDurationMillis}
          min={1}
          onChange={(next) => onChange({ ...value, processorLeaseDurationMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.processorHeartbeatIntervalMillis')}
          value={value.processorHeartbeatIntervalMillis}
          min={1}
          onChange={(next) => onChange({ ...value, processorHeartbeatIntervalMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.threadResolveFailureDelayMillis')}
          value={value.threadResolveFailureDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, threadResolveFailureDelayMillis: next })}
        />
      </SettingsCard>

      <SettingsCard title={t('settings.section.advanced.dispatcher.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.modelDispatchBusyFallbackDelayMillis')}
          value={value.modelDispatchBusyFallbackDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, modelDispatchBusyFallbackDelayMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.toolPreflightFailureDelayMillis')}
          value={value.toolPreflightFailureDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, toolPreflightFailureDelayMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.toolDispatchBusyFallbackDelayMillis')}
          value={value.toolDispatchBusyFallbackDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, toolDispatchBusyFallbackDelayMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherLeaseDurationMillis')}
          value={value.dispatcherLeaseDurationMillis}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherLeaseDurationMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherPollIntervalMillis')}
          value={value.dispatcherPollIntervalMillis}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherPollIntervalMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherRejectionDelayMillis')}
          value={value.dispatcherRejectionDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherRejectionDelayMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherMaxDispatchTasks')}
          value={value.dispatcherMaxDispatchTasks}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherMaxDispatchTasks: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherWorkerConcurrency')}
          value={value.dispatcherWorkerConcurrency}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherWorkerConcurrency: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.dispatcherWorkerQueueCapacity')}
          value={value.dispatcherWorkerQueueCapacity}
          min={1}
          onChange={(next) => onChange({ ...value, dispatcherWorkerQueueCapacity: next })}
        />
      </SettingsCard>

      <SettingsCard title={t('settings.section.advanced.canvas.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.canvasRealtimeMaxLength')}
          value={value.canvasRealtimeMaxLength}
          min={1}
          onChange={(next) => onChange({ ...value, canvasRealtimeMaxLength: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.canvasFunctionExecutorCoreSize')}
          value={value.canvasFunctionExecutorCoreSize}
          min={1}
          onChange={(next) => onChange({ ...value, canvasFunctionExecutorCoreSize: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.canvasFunctionExecutorMaxSize')}
          value={value.canvasFunctionExecutorMaxSize}
          min={1}
          onChange={(next) => onChange({ ...value, canvasFunctionExecutorMaxSize: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.canvasFunctionExecutorQueueCapacity')}
          value={value.canvasFunctionExecutorQueueCapacity}
          min={1}
          onChange={(next) => onChange({ ...value, canvasFunctionExecutorQueueCapacity: next })}
        />
      </SettingsCard>

      <SettingsCard title={t('settings.section.advanced.applicationEvent.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.applicationEventQueueCapacity')}
          value={value.applicationEventQueueCapacity}
          min={1}
          onChange={(next) => onChange({ ...value, applicationEventQueueCapacity: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.applicationEventMaxBytes')}
          value={value.applicationEventMaxBytes}
          min={1}
          onChange={(next) => onChange({ ...value, applicationEventMaxBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.applicationEventSendTimeoutMillis')}
          value={value.applicationEventSendTimeoutMillis}
          min={1}
          onChange={(next) => onChange({ ...value, applicationEventSendTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.applicationEventHeartbeatIntervalMillis')}
          value={value.applicationEventHeartbeatIntervalMillis}
          min={1}
          onChange={(next) => onChange({ ...value, applicationEventHeartbeatIntervalMillis: next })}
        />
      </SettingsCard>

      <SettingsCard title={t('settings.section.advanced.workNotification.title')}>
        <SettingsNumberField
          label={t('settings.field.advanced.postgresqlWorkNotificationPollMillis')}
          value={value.postgresqlWorkNotificationPollMillis}
          min={1}
          onChange={(next) => onChange({ ...value, postgresqlWorkNotificationPollMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.postgresqlWorkReconnectBackoffMillis')}
          value={value.postgresqlWorkReconnectBackoffMillis}
          min={1}
          onChange={(next) => onChange({ ...value, postgresqlWorkReconnectBackoffMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.advanced.redisRealtimeRetryDelayMillis')}
          value={value.redisRealtimeRetryDelayMillis}
          min={1}
          onChange={(next) => onChange({ ...value, redisRealtimeRetryDelayMillis: next })}
        />
      </SettingsCard>
    </div>
  )
}
