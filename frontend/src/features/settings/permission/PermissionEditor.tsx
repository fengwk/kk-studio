import { useI18n } from '@/shared/i18n'
import {
  addRule,
  addTool,
  moveRule,
  removeRule,
  removeTool,
  renameTool,
  updateRule,
} from '@/features/settings/permission-utils'
import type { PermissionGroupDraft } from '@/features/settings/system-settings-draft'
import { SettingsCard } from '@/features/settings/settings-primitives'
import type { SystemSettingsSchemaOption } from '@/shared/api/contracts/system-settings'

/**
 * 保序 permission 规则编辑器（tool 名 + 该 tool 下有序规则数组）。
 *
 * - 规则数组顺序即求值顺序，只能在同 tool 内上移/下移，禁止跨 tool 重排；
 * - 重命名 tool 时目标名冲突会确定性合并（源规则追加到目标之后）而不是丢规则；
 * - 空 tool 名 / 空 pattern 呈现可修复的内联错误态，保存会被 codec 阻止；
 * - 无原始 JSON 编辑器。
 */
export function PermissionEditor({
  groups,
  onChange,
  options,
}: {
  groups: PermissionGroupDraft[]
  onChange: (next: PermissionGroupDraft[]) => void
  options: SystemSettingsSchemaOption[]
}) {
  const { t } = useI18n()

  return (
    <div className="permission-editor" data-permission-editor>
      {groups.length === 0 ? (
        <p className="settings-hint">{t('settings.permission.empty')}</p>
      ) : null}

      {groups.map((group, groupIndex) => {
        const toolNameBlank = group.tool.trim() === ''
        return (
          <section
            key={groupIndex}
            className="permission-group"
            data-permission-group
            aria-label={t('settings.permission.groupAriaLabel', { tool: group.tool || '…' })}
          >
            <div className="permission-group-header">
              <div className="permission-tool-name-wrap">
                <label className="permission-tool-label" htmlFor={`perm-tool-${groupIndex}`}>
                  {t('settings.permission.toolName')}
                </label>
                <input
                  id={`perm-tool-${groupIndex}`}
                  className="settings-input permission-tool-input"
                  value={group.tool}
                  data-invalid={toolNameBlank || undefined}
                  aria-invalid={toolNameBlank}
                  onChange={(event) => onChange(renameTool(groups, groupIndex, event.target.value))}
                />
                {toolNameBlank ? (
                  <span className="permission-error" role="alert">
                    {t('settings.error.permissionToolNameRequired')}
                  </span>
                ) : null}
              </div>
              <button
                type="button"
                className="settings-icon-button danger"
                aria-label={t('settings.permission.removeTool', { tool: group.tool || '…' })}
                onClick={() => onChange(removeTool(groups, groupIndex))}
              >
                {t('settings.permission.removeToolShort')}
              </button>
            </div>

            {group.rules.length === 0 ? (
              <p className="settings-hint">{t('settings.permission.noRules')}</p>
            ) : null}

            <div className="permission-rule-list">
              {group.rules.map((rule, ruleIndex) => {
                const patternBlank = rule.pattern.trim() === ''
                const lastRule = ruleIndex === group.rules.length - 1
                return (
                  <div className="permission-rule" key={ruleIndex} data-permission-rule>
                    <div className="permission-rule-pattern">
                      <input
                        className="settings-input permission-pattern-input"
                        value={rule.pattern}
                        placeholder={t('settings.permission.patternPlaceholder')}
                        data-invalid={patternBlank || undefined}
                        aria-invalid={patternBlank}
                        aria-label={t('settings.permission.patternAriaLabel', { index: ruleIndex + 1 })}
                        onChange={(event) =>
                          onChange(
                            updateRule(groups, groupIndex, ruleIndex, {
                              pattern: event.target.value,
                            }),
                          )
                        }
                      />
                      {patternBlank ? (
                        <span className="permission-error" role="alert">
                          {t('settings.error.permissionPatternRequired')}
                        </span>
                      ) : null}
                    </div>
                    <select
                      className="settings-input permission-action-select"
                      value={rule.action}
                      aria-label={t('settings.permission.actionAriaLabel', { index: ruleIndex + 1 })}
                      onChange={(event) =>
                        onChange(
                          updateRule(groups, groupIndex, ruleIndex, {
                            action: event.target.value as PermissionGroupDraft['rules'][number]['action'],
                          }),
                        )
                      }
                    >
                      {options.map((option) => (
                        <option key={option.value} value={option.value}>
                          {t(option.labelKey)}
                        </option>
                      ))}
                    </select>
                    <div className="permission-rule-actions">
                      <button
                        type="button"
                        className="settings-icon-button"
                        aria-label={t('settings.permission.moveUp')}
                        disabled={ruleIndex === 0}
                        onClick={() => onChange(moveRule(groups, groupIndex, ruleIndex, -1))}
                      >
                        ↑
                      </button>
                      <button
                        type="button"
                        className="settings-icon-button"
                        aria-label={t('settings.permission.moveDown')}
                        disabled={lastRule}
                        onClick={() => onChange(moveRule(groups, groupIndex, ruleIndex, 1))}
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        className="settings-icon-button danger"
                        aria-label={t('settings.permission.removeRule', { index: ruleIndex + 1 })}
                        onClick={() => onChange(removeRule(groups, groupIndex, ruleIndex))}
                      >
                        ✕
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>

            <button
              type="button"
              className="settings-button"
              onClick={() => onChange(addRule(groups, groupIndex))}
            >
              {t('settings.permission.addRule')}
            </button>
          </section>
        )
      })}

      <button type="button" className="settings-button" onClick={() => onChange(addTool(groups))}>
        {t('settings.permission.addTool')}
      </button>
    </div>
  )
}

export function PermissionEditorCard({
  groups,
  onChange,
  options,
}: {
  groups: PermissionGroupDraft[]
  onChange: (next: PermissionGroupDraft[]) => void
  options: SystemSettingsSchemaOption[]
}) {
  const { t } = useI18n()
  return (
    <SettingsCard
      title={t('settings.section.tool.permission.title')}
      description="settings.section.tool.permission.description"
      timing="nextInvocation"
    >
      <PermissionEditor groups={groups} onChange={onChange} options={options} />
    </SettingsCard>
  )
}
