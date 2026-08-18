import { useI18n } from '@/shared/i18n'
import {
  RestartNotice,
  SettingsCard,
  SettingsNumberField,
  SettingsSwitchRow,
} from '@/features/settings/settings-primitives'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsStorageMediaDraft } from '@/features/settings/system-settings-draft'

/** storageMedia section：上传/S3 预签名与 Canvas 媒体处理预算（启动快照，重启后生效）。 */
export function StorageMediaTab({
  value,
  onChange,
}: SectionEditorProps<SystemSettingsStorageMediaDraft>) {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack">
      <RestartNotice />

      <SettingsCard
        title={t('settings.section.storageMedia.upload.title')}
        description="settings.section.storageMedia.upload.description"
      >
        <SettingsNumberField
          label={t('settings.field.storageMedia.uploadExpiresSeconds')}
          value={value.uploadExpiresSeconds}
          min={1}
          onChange={(next) => onChange({ ...value, uploadExpiresSeconds: next })}
        />
        <SettingsSwitchRow
          label={t('settings.field.storageMedia.s3Enabled')}
          checked={value.s3Enabled}
          onChange={(next) => onChange({ ...value, s3Enabled: next })}
        />
        <SettingsNumberField
          label={t('settings.field.storageMedia.s3PresignDefaultExpiresSeconds')}
          value={value.s3PresignDefaultExpiresSeconds}
          min={1}
          onChange={(next) => onChange({ ...value, s3PresignDefaultExpiresSeconds: next })}
        />
        <SettingsNumberField
          label={t('settings.field.storageMedia.s3PresignMaxExpiresSeconds')}
          value={value.s3PresignMaxExpiresSeconds}
          min={1}
          onChange={(next) => onChange({ ...value, s3PresignMaxExpiresSeconds: next })}
        />
      </SettingsCard>

      <SettingsCard
        title={t('settings.section.storageMedia.canvasMedia.title')}
        description="settings.section.storageMedia.canvasMedia.description"
      >
        <SettingsNumberField
          label={t('settings.field.storageMedia.canvasMediaProcessTimeoutMillis')}
          value={value.canvasMediaProcessTimeoutMillis}
          min={1}
          onChange={(next) => onChange({ ...value, canvasMediaProcessTimeoutMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.storageMedia.thumbnailMaxDimension')}
          value={value.thumbnailMaxDimension}
          min={1}
          onChange={(next) => onChange({ ...value, thumbnailMaxDimension: next })}
        />
        <SettingsNumberField
          label={t('settings.field.storageMedia.thumbnailQuality')}
          value={value.thumbnailQuality}
          min={1}
          max={100}
          onChange={(next) => onChange({ ...value, thumbnailQuality: next })}
        />
      </SettingsCard>
    </div>
  )
}
