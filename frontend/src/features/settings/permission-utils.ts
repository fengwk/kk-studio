import type { PermissionGroupDraft, PermissionRuleDraft } from '@/features/settings/system-settings-draft'

/**
 * 保序 permission 编辑器的纯操作。所有函数都返回新数组、不修改入参。
 *
 * 重命名语义（防丢规则）：新 tool 名与其它已有分组冲突时，把源分组的规则
 * 「追加到目标分组现有 rules 之后」并移除源分组（确定性合并，不覆盖丢失）；
 * 新名称为空串时不合并，只清空源分组名（呈现可修复的空态）。
 */

export function cloneGroups(groups: readonly PermissionGroupDraft[]): PermissionGroupDraft[] {
  return groups.map((group) => ({ tool: group.tool, rules: group.rules.map((rule) => ({ ...rule })) }))
}

export function renameTool(
  groups: readonly PermissionGroupDraft[],
  index: number,
  nextName: string,
): PermissionGroupDraft[] {
  const copy = cloneGroups(groups)
  const source = copy[index]
  if (nextName !== '') {
    const collision = copy.findIndex(
      (group, groupIndex) => groupIndex !== index && group.tool === nextName,
    )
    if (collision >= 0) {
      copy[collision] = {
        tool: nextName,
        rules: [...copy[collision].rules, ...source.rules],
      }
      copy.splice(index, 1)
      return copy
    }
  }
  copy[index] = { ...source, tool: nextName }
  return copy
}

export function addTool(groups: readonly PermissionGroupDraft[]): PermissionGroupDraft[] {
  return [...cloneGroups(groups), { tool: '', rules: [] }]
}

export function removeTool(
  groups: readonly PermissionGroupDraft[],
  index: number,
): PermissionGroupDraft[] {
  const copy = cloneGroups(groups)
  copy.splice(index, 1)
  return copy
}

export function addRule(
  groups: readonly PermissionGroupDraft[],
  groupIndex: number,
): PermissionGroupDraft[] {
  const copy = cloneGroups(groups)
  copy[groupIndex] = {
    ...copy[groupIndex],
    rules: [...copy[groupIndex].rules, { pattern: '', action: 'ask' }],
  }
  return copy
}

export function updateRule(
  groups: readonly PermissionGroupDraft[],
  groupIndex: number,
  ruleIndex: number,
  patch: Partial<PermissionRuleDraft>,
): PermissionGroupDraft[] {
  const copy = cloneGroups(groups)
  copy[groupIndex] = {
    ...copy[groupIndex],
    rules: copy[groupIndex].rules.map((rule, index) =>
      index === ruleIndex ? { ...rule, ...patch } : rule,
    ),
  }
  return copy
}

export function removeRule(
  groups: readonly PermissionGroupDraft[],
  groupIndex: number,
  ruleIndex: number,
): PermissionGroupDraft[] {
  const copy = cloneGroups(groups)
  copy[groupIndex] = {
    ...copy[groupIndex],
    rules: copy[groupIndex].rules.filter((_, index) => index !== ruleIndex),
  }
  return copy
}

/** 在同一 tool 分组内把规则上移/下移一格；越界时返回原数组。 */
export function moveRule(
  groups: readonly PermissionGroupDraft[],
  groupIndex: number,
  ruleIndex: number,
  direction: -1 | 1,
): PermissionGroupDraft[] {
  const targetIndex = ruleIndex + direction
  const rules = groups[groupIndex]?.rules
  if (!rules || targetIndex < 0 || targetIndex >= rules.length) {
    return groups as PermissionGroupDraft[]
  }
  const copy = cloneGroups(groups)
  const group = copy[groupIndex]
  const next = [...group.rules]
  const [moved] = next.splice(ruleIndex, 1)
  next.splice(targetIndex, 0, moved)
  copy[groupIndex] = { ...group, rules: next }
  return copy
}

export function hasBlankPattern(groups: readonly PermissionGroupDraft[]): boolean {
  return groups.some((group) => group.rules.some((rule) => rule.pattern.trim() === ''))
}

export function hasBlankToolName(groups: readonly PermissionGroupDraft[]): boolean {
  return groups.some((group) => group.tool.trim() === '')
}
