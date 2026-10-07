import { useQuery } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, X } from 'lucide-react'
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
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

/**
 * 保序 permission 规则编辑器（模型可见 tool name + 该 tool 下有序规则数组）。
 *
 * - 规则数组顺序即求值顺序，只能在同 tool 内上移/下移，禁止跨 tool 重排；
 * - 重命名 tool name 时目标 name 冲突会确定性合并（源规则追加到目标之后）而不是丢规则；
 * - 空 tool name / 空 pattern 呈现可修复的内联错误态，保存会被 codec 阻止；
 * - tool name 从运行时 catalog 选择；catalog 缺失的已保存 name 保留为可改选项。
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
  const catalogNames = permissionToolCandidates.map((tool) => tool.value)

  return (
    <div className="permission-editor" data-permission-editor>
      {groups.length === 0 ? (
        <StateBlock title={t('settings.permission.empty')} />
      ) : null}

      {groups.map((group, groupIndex) => {
        const toolNameBlank = group.tool.trim() === ''
        const toolUnavailable = !toolNameBlank && !catalogNames.includes(group.tool)
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
                  aria-invalid={toolNameBlank}
                  options={toolOptions}
                  onChange={(tool) => onChange(renameTool(groups, groupIndex, tool))}
                />
                {toolNameBlank ? (
                  <span className="permission-error" role="alert">
                    {t('settings.error.permissionToolNameRequired')}
                  </span>
                ) : null}
              </div>
              <Button
                variant="ghost"
                danger
                size="compact"
                className="permission-remove-tool"
                aria-label={t('settings.permission.removeTool', { tool: group.tool || '…' })}
                onClick={() => onChange(removeTool(groups, groupIndex))}
              >
                {t('settings.permission.removeToolShort')}
              </Button>
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
                      <TextInput
                        className="permission-pattern-input"
                        value={rule.pattern}
                        placeholder={t('settings.permission.patternPlaceholder')}
                        invalid={patternBlank}
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
                      <IconButton
                        label={t('settings.permission.moveUp')}
                        size="compact"
                        disabled={ruleIndex === 0}
                        onClick={() => onChange(moveRule(groups, groupIndex, ruleIndex, -1))}
                      >
                        <ArrowUp aria-hidden="true" />
                      </IconButton>
                      <IconButton
                        label={t('settings.permission.moveDown')}
                        size="compact"
                        disabled={lastRule}
                        onClick={() => onChange(moveRule(groups, groupIndex, ruleIndex, 1))}
                      >
                        <ArrowDown aria-hidden="true" />
                      </IconButton>
                      <IconButton
                        label={t('settings.permission.removeRule', { index: ruleIndex + 1 })}
                        danger
                        size="compact"
                        onClick={() => onChange(removeRule(groups, groupIndex, ruleIndex))}
                      >
                        <X aria-hidden="true" />
                      </IconButton>
                    </div>
                  </div>
                )
              })}
            </div>

            <Button
              variant="ghost"
              onClick={() => onChange(addRule(groups, groupIndex))}
            >
              {t('settings.permission.addRule')}
            </Button>
          </section>
        )
      })}

      <Button variant="ghost" onClick={() => onChange(addTool(groups))}>
        {t('settings.permission.addTool')}
      </Button>
    </div>
  )
}
