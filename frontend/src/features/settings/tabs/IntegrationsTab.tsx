import type { ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'
import {
  RestartNotice,
  SettingsCard,
  SettingsNumberField,
  SettingsSwitchRow,
  SettingsTextField,
} from '@/features/settings/settings-primitives'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsIntegrationsDraft } from '@/features/settings/system-settings-draft'

/** 单个集成卡：启用/付费开关 + 全部非敏感字段（长生命周期客户端，重启后生效）。 */
function IntegrationCard({
  titleKey,
  descriptionKey,
  enabled,
  enabledLabel,
  enabledChange,
  children,
}: {
  titleKey: string
  descriptionKey: string
  enabled: boolean
  enabledLabel: string
  enabledChange: (next: boolean) => void
  children: ReactNode
}) {
  const { t } = useI18n()
  return (
    <SettingsCard title={t(titleKey)} description={descriptionKey}>
      <SettingsSwitchRow
        label={enabledLabel}
        checked={enabled}
        onChange={enabledChange}
        ariaLabel={enabledLabel}
      />
      {children}
    </SettingsCard>
  )
}

/** integrations section：外部媒体/生成集成的非敏感运行参数（启动快照，重启后生效）。 */
export function IntegrationsTab({
  value,
  onChange,
}: SectionEditorProps<SystemSettingsIntegrationsDraft>) {
  const { t } = useI18n()

  const patchComfyui = (patch: Partial<typeof value.comfyui>) => {
    onChange({ ...value, comfyui: { ...value.comfyui, ...patch } })
  }
  const patchOpenCliHub = (patch: Partial<typeof value.openCliHub>) => {
    onChange({ ...value, openCliHub: { ...value.openCliHub, ...patch } })
  }
  const patchSeedance = (patch: Partial<typeof value.seedance>) => {
    onChange({ ...value, seedance: { ...value.seedance, ...patch } })
  }
  const patchGptImage2 = (patch: Partial<typeof value.gptImage2>) => {
    onChange({ ...value, gptImage2: { ...value.gptImage2, ...patch } })
  }
  const patchMiniMaxH3 = (patch: Partial<typeof value.minimaxH3>) => {
    onChange({ ...value, minimaxH3: { ...value.minimaxH3, ...patch } })
  }

  const comfyui = value.comfyui
  const openCliHub = value.openCliHub
  const seedance = value.seedance
  const gptImage2 = value.gptImage2
  const minimaxH3 = value.minimaxH3

  return (
    <div className="settings-section-stack">
      <RestartNotice />

      <IntegrationCard
        titleKey="settings.section.integrations.comfyui.title"
        descriptionKey="settings.section.integrations.comfyui.description"
        enabled={comfyui.enabled}
        enabledLabel={t('settings.field.integrations.comfyui.enabled')}
        enabledChange={(next) => patchComfyui({ enabled: next })}
      >
        <SettingsTextField
          label={t('settings.field.integrations.comfyui.baseUrl')}
          value={comfyui.baseUrl}
          onChange={(next) => patchComfyui({ baseUrl: next })}
          placeholder="https://comfy.example.com"
        />
        <SettingsNumberField
          label={t('settings.field.integrations.comfyui.connectTimeoutMillis')}
          value={comfyui.connectTimeoutMillis}
          min={1}
          onChange={(next) => patchComfyui({ connectTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.comfyui.readTimeoutMillis')}
          value={comfyui.readTimeoutMillis}
          min={1}
          onChange={(next) => patchComfyui({ readTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.comfyui.websocketTimeoutMillis')}
          value={comfyui.websocketTimeoutMillis}
          min={1}
          onChange={(next) => patchComfyui({ websocketTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.comfyui.maxInputFileBytes')}
          value={comfyui.maxInputFileBytes}
          min={1}
          onChange={(next) => patchComfyui({ maxInputFileBytes: next })}
        />
      </IntegrationCard>

      <IntegrationCard
        titleKey="settings.section.integrations.openCliHub.title"
        descriptionKey="settings.section.integrations.openCliHub.description"
        enabled={openCliHub.enabled}
        enabledLabel={t('settings.field.integrations.openCliHub.enabled')}
        enabledChange={(next) => patchOpenCliHub({ enabled: next })}
      >
        <SettingsTextField
          label={t('settings.field.integrations.openCliHub.baseUrl')}
          value={openCliHub.baseUrl}
          onChange={(next) => patchOpenCliHub({ baseUrl: next })}
          placeholder="http://vps-opencli-hub:8080"
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.connectTimeoutMillis')}
          value={openCliHub.connectTimeoutMillis}
          min={1}
          onChange={(next) => patchOpenCliHub({ connectTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.requestTimeoutMillis')}
          value={openCliHub.requestTimeoutMillis}
          min={1000}
          onChange={(next) => patchOpenCliHub({ requestTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.longPollTimeoutMillis')}
          value={openCliHub.longPollTimeoutMillis}
          min={121000}
          onChange={(next) => patchOpenCliHub({ longPollTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.streamBufferBytes')}
          value={openCliHub.streamBufferBytes}
          min={1024}
          onChange={(next) => patchOpenCliHub({ streamBufferBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.maxJsonResponseBytes')}
          value={openCliHub.maxJsonResponseBytes}
          min={1024}
          onChange={(next) => patchOpenCliHub({ maxJsonResponseBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.maxErrorResponseBytes')}
          value={openCliHub.maxErrorResponseBytes}
          min={256}
          onChange={(next) => patchOpenCliHub({ maxErrorResponseBytes: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.openCliHub.maxOutputChars')}
          value={openCliHub.maxOutputChars}
          min={1024}
          onChange={(next) => patchOpenCliHub({ maxOutputChars: next })}
        />
      </IntegrationCard>

      <IntegrationCard
        titleKey="settings.section.integrations.seedance.title"
        descriptionKey="settings.section.integrations.seedance.description"
        enabled={seedance.enabled}
        enabledLabel={t('settings.field.integrations.seedance.enabled')}
        enabledChange={(next) => patchSeedance({ enabled: next })}
      >
        <SettingsTextField
          label={t('settings.field.integrations.seedance.workspaceId')}
          value={seedance.workspaceId}
          onChange={(next) => patchSeedance({ workspaceId: next })}
          placeholder="…"
        />
        <SettingsNumberField
          label={t('settings.field.integrations.seedance.retry')}
          value={seedance.retry}
          min={0}
          max={5}
          onChange={(next) => patchSeedance({ retry: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.seedance.hubExecutionTimeoutMillis')}
          value={seedance.hubExecutionTimeoutMillis}
          min={1000}
          onChange={(next) => patchSeedance({ hubExecutionTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.seedance.statusPollIntervalMillis')}
          value={seedance.statusPollIntervalMillis}
          min={1}
          onChange={(next) => patchSeedance({ statusPollIntervalMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.seedance.maxWaitMillis')}
          value={seedance.maxWaitMillis}
          min={1000}
          onChange={(next) => patchSeedance({ maxWaitMillis: next })}
        />
      </IntegrationCard>

      <IntegrationCard
        titleKey="settings.section.integrations.gptImage2.title"
        descriptionKey="settings.section.integrations.gptImage2.description"
        enabled={gptImage2.paidEnabled}
        enabledLabel={t('settings.field.integrations.gptImage2.paidEnabled')}
        enabledChange={(next) => patchGptImage2({ paidEnabled: next })}
      >
        <SettingsNumberField
          label={t('settings.field.integrations.gptImage2.askTimeoutSeconds')}
          value={gptImage2.askTimeoutSeconds}
          min={1}
          max={1740}
          onChange={(next) => patchGptImage2({ askTimeoutSeconds: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.gptImage2.hubExecutionTimeoutMillis')}
          value={gptImage2.hubExecutionTimeoutMillis}
          min={31000}
          onChange={(next) => patchGptImage2({ hubExecutionTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.gptImage2.maxWaitMillis')}
          value={gptImage2.maxWaitMillis}
          min={1000}
          onChange={(next) => patchGptImage2({ maxWaitMillis: next })}
        />
      </IntegrationCard>

      <IntegrationCard
        titleKey="settings.section.integrations.minimaxH3.title"
        descriptionKey="settings.section.integrations.minimaxH3.description"
        enabled={minimaxH3.enabled}
        enabledLabel={t('settings.field.integrations.minimaxH3.enabled')}
        enabledChange={(next) => patchMiniMaxH3({ enabled: next })}
      >
        <SettingsTextField
          label={t('settings.field.integrations.minimaxH3.promptAgentName')}
          value={minimaxH3.promptAgentName}
          onChange={(next) => patchMiniMaxH3({ promptAgentName: next })}
        />
        <SettingsTextField
          label={t('settings.field.integrations.minimaxH3.promptEnvironmentName')}
          value={minimaxH3.promptEnvironmentName}
          onChange={(next) => patchMiniMaxH3({ promptEnvironmentName: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.minimaxH3.promptMaxWaitMillis')}
          value={minimaxH3.promptMaxWaitMillis}
          min={1}
          onChange={(next) => patchMiniMaxH3({ promptMaxWaitMillis: next })}
        />
        <SettingsTextField
          label={t('settings.field.integrations.minimaxH3.comfyBaseUrl')}
          value={minimaxH3.comfyBaseUrl}
          onChange={(next) => patchMiniMaxH3({ comfyBaseUrl: next })}
          placeholder="https://comfy.example.com"
        />
        <SettingsNumberField
          label={t('settings.field.integrations.minimaxH3.comfyConnectTimeoutMillis')}
          value={minimaxH3.comfyConnectTimeoutMillis}
          min={1}
          onChange={(next) => patchMiniMaxH3({ comfyConnectTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.minimaxH3.comfyRequestTimeoutMillis')}
          value={minimaxH3.comfyRequestTimeoutMillis}
          min={1}
          onChange={(next) => patchMiniMaxH3({ comfyRequestTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.minimaxH3.comfyPollIntervalMillis')}
          value={minimaxH3.comfyPollIntervalMillis}
          min={1}
          onChange={(next) => patchMiniMaxH3({ comfyPollIntervalMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.integrations.minimaxH3.comfyMaxWaitMillis')}
          value={minimaxH3.comfyMaxWaitMillis}
          min={1}
          onChange={(next) => patchMiniMaxH3({ comfyMaxWaitMillis: next })}
        />
      </IntegrationCard>
    </div>
  )
}
