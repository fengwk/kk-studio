import { useQuery } from '@tanstack/react-query'
import { buildPermissionToolCandidates } from '@/features/ai/catalog/agent-capability-candidates'
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
import { agentService } from '@/shared/api/agent-service'
import type { SystemSettingsSchemaOption } from '@/shared/api/contracts/system-settings'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { Select } from '@/shared/ui/console/Select'

/**
 * 保序 permission 规则编辑器（tool ID + 该 tool 下有序规则数组）。
 *
 * - 规则数组顺序即求值顺序，只能在同 tool 内上移/下移，禁止跨 tool 重排；
 * - 重命名 tool ID 时目标 ID 冲突会确定性合并（源规则追加到目标之后）而不是丢规则；
 * - 空 tool ID / 空 pattern 呈现可修复的内联错误态，保存会被 codec 阻止；
 * - tool ID 从运行时 catalog 选择；catalog 缺失的已保存 ID 保留为可改选项。
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
  const toolsQuery = useQuery({
    queryKey: queryKeys.tools.list,
    queryFn: () => agentService.listTools(),
  })
  const permissionToolCandidates = buildPermissionToolCandidates(toolsQuery.data ?? [])
  const catalogIds = permissionToolCandidates.map((tool) => tool.value)

  return (
    <div className="permission-editor" data-permission-editor>
      {groups.length === 0 ? (
        <p className="settings-hint">{t('settings.permission.empty')}</p>
      ) : null}

      {groups.map((group, groupIndex) => {
        const toolIdBlank = group.tool.trim() === ''
        const toolUnavailable = !toolIdBlank && !catalogIds.includes(group.tool)
        const toolOptions = [
          ...(toolUnavailable
            ? [
                {
                  value: group.tool,
                  label: `${group.tool} (${t('ai.catalog.form.unavailable')})`,
                },
              ]
            : []),
          ...permissionToolCandidates.map((candidate) => ({
            value: candidate.value,
            label: candidate.name,
          })),
        ]
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
                <Select
                  id={`perm-tool-${groupIndex}`}
                  value={group.tool}
                  placeholder={t('shared.selectPlaceholder')}
                  aria-invalid={toolIdBlank}
                  options={toolOptions}
                  onChange={(tool) => onChange(renameTool(groups, groupIndex, tool))}
                />
                {toolIdBlank ? (
                  <span className="permission-error" role="alert">
                    {t('settings.error.permissionToolNameRequired')}
                  </span>
                ) : null}
              </div>
              <button
                type="button"
                className="settings-button danger permission-remove-tool"
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
                    <Select
                      compact
                      className="permission-action-select"
                      value={rule.action}
                      aria-label={t('settings.permission.actionAriaLabel', { index: ruleIndex + 1 })}
                      options={options.map((option) => ({
                        value: option.value,
                        label: t(option.labelKey),
                      }))}
                      onChange={(action) =>
                        onChange(
                          updateRule(groups, groupIndex, ruleIndex, {
                            action: action as PermissionGroupDraft['rules'][number]['action'],
                          }),
                        )
                      }
                    />
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
