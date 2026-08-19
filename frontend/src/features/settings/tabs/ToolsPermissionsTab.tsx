import { useI18n } from '@/shared/i18n'
import {
  SettingsCard,
  SettingsNumberField,
  SettingsSwitchRow,
} from '@/features/settings/settings-primitives'
import { PermissionEditorCard } from '@/features/settings/permission/PermissionEditor'
import type { SectionEditorProps } from '@/features/settings/settings-tabs'
import type { SystemSettingsToolDraft } from '@/features/settings/system-settings-draft'

/**
 * tool section：权限规则编辑器（下次调用生效）+ 默认 YOLO（新建对话生效）
 * + 模型/工具 Gateway 与 skill 加载预算（启动快照，重启后生效）。
 */
export function ToolsPermissionsTab({
  value,
  onChange,
}: SectionEditorProps<SystemSettingsToolDraft>) {
  const { t } = useI18n()

  const updatePermission = (permission: SystemSettingsToolDraft['permission']) => {
    onChange({ ...value, permission })
  }

  return (
    <div className="settings-section-stack">
      <PermissionEditorCard groups={value.permission} onChange={updatePermission} />

      <SettingsCard
        title={t('settings.section.tool.yolo.title')}
        description="settings.section.tool.yolo.description"
        timing="nextChat"
      >
        <SettingsSwitchRow
          label={t('settings.field.tool.defaultYolo')}
          checked={value.defaultYolo}
          onChange={(next) => onChange({ ...value, defaultYolo: next })}
        />
      </SettingsCard>

      <SettingsCard
        title={t('settings.section.tool.gateway.title')}
        description="settings.section.tool.gateway.description"
        timing="restart"
      >
        <SettingsNumberField
          label={t('settings.field.tool.modelGatewayBusyRetryMillis')}
          value={value.modelGatewayBusyRetryMillis}
          min={1}
          onChange={(next) => onChange({ ...value, modelGatewayBusyRetryMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.tool.toolGatewayBusyRetryMillis')}
          value={value.toolGatewayBusyRetryMillis}
          min={1}
          onChange={(next) => onChange({ ...value, toolGatewayBusyRetryMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.tool.toolGatewayOverloadRetryMillis')}
          value={value.toolGatewayOverloadRetryMillis}
          min={1}
          onChange={(next) => onChange({ ...value, toolGatewayOverloadRetryMillis: next })}
        />
        <SettingsNumberField
          label={t('settings.field.tool.skillLoadTimeoutMillis')}
          value={value.skillLoadTimeoutMillis}
          min={1}
          onChange={(next) => onChange({ ...value, skillLoadTimeoutMillis: next })}
        />
      </SettingsCard>
    </div>
  )
}
