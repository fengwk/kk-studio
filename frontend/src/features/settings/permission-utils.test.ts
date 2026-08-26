import { describe, expect, it } from 'vitest'
import {
  addRule,
  addTool,
  hasBlankPattern,
  hasBlankToolName,
  moveRule,
  removeRule,
  removeTool,
  renameTool,
  updateRule,
} from '@/features/settings/permission-utils'
import type { PermissionGroupDraft } from '@/features/settings/system-settings-draft'

function groups(...entries: Array<[string, Array<[string, 'allow' | 'ask' | 'deny']>]>): PermissionGroupDraft[] {
  return entries.map(([tool, rules]) => ({
    tool,
    rules: rules.map(([pattern, action]) => ({ pattern, action })),
  }))
}

describe('permission-utils', () => {
  it('renames a tool without collision by keeping its rules at the new name', () => {
    const next = renameTool(groups(['base.bash', [['*', 'ask']]]), 0, 'base.read')
    expect(next).toEqual([{ tool: 'base.read', rules: [{ pattern: '*', action: 'ask' }] }])
  })

  it('merges deterministically when renaming collides with an existing tool (source rules appended after target)', () => {
    const next = renameTool(
      groups(
        ['base.bash', [['scripts/*', 'deny']]],
        ['base.write', [['*', 'ask'], ['node_modules/**', 'deny']]],
      ),
      0,
      'base.write',
    )
    // base.bash 的规则追加到 base.write 现有规则之后，且 base.bash 分组被移除。
    expect(next).toEqual([
      {
        tool: 'base.write',
        rules: [
          { pattern: '*', action: 'ask' },
          { pattern: 'node_modules/**', action: 'deny' },
          { pattern: 'scripts/*', action: 'deny' },
        ],
      },
    ])
  })

  it('blank rename never merges and only clears the source name', () => {
    const next = renameTool(
      groups(['base.bash', [['*', 'ask']]], ['base.write', [['*', 'ask']]]),
      0,
      '  ',
    )
    expect(next[0]).toEqual({ tool: '  ', rules: [{ pattern: '*', action: 'ask' }] })
    expect(next).toHaveLength(2)
  })

  it('handles out-of-bounds move as a no-op and moves within the same tool only', () => {
    const source = groups(['tool', [['a', 'allow'], ['b', 'ask'], ['c', 'deny']]])
    expect(JSON.stringify(moveRule(source, 0, 0, -1))).toBe(JSON.stringify(source))
    expect(JSON.stringify(moveRule(source, 0, 2, 1))).toBe(JSON.stringify(source))

    const moved = moveRule(source, 0, 1, -1)
    expect(moved[0]!.rules.map((rule) => rule.pattern)).toEqual(['b', 'a', 'c'])
    const movedDown = moveRule(source, 0, 0, 1)
    expect(movedDown[0]!.rules.map((rule) => rule.pattern)).toEqual(['b', 'a', 'c'])
  })

  it('adds/removes tools and rules without mutating the input', () => {
    const source = groups(['tool', [['a', 'allow']]])
    const withRule = addRule(source, 0)
    expect(withRule[0]!.rules).toHaveLength(2)
    expect(withRule[0]!.rules[1]).toEqual({ pattern: '', action: 'ask' })
    expect(source[0]!.rules).toHaveLength(1)

    expect(removeRule(withRule, 0, 0)[0]!.rules).toHaveLength(1)

    const withTool = addTool(source)
    expect(withTool).toHaveLength(2)
    expect(withTool[1]).toEqual({ tool: '', rules: [] })

    expect(removeTool(withTool, 0)).toHaveLength(1)
  })

  it('updates a single rule in place', () => {
    const next = updateRule(groups(['tool', [['a', 'allow']]]), 0, 0, { action: 'deny', pattern: 'b' })
    expect(next[0]!.rules[0]).toEqual({ pattern: 'b', action: 'deny' })
  })

  it('detects blank tool names and blank patterns for validation states', () => {
    expect(hasBlankToolName(groups(['', [['a', 'allow']]]))).toBe(true)
    expect(hasBlankToolName(groups(['tool', [['a', 'allow']]]))).toBe(false)
    expect(hasBlankPattern(groups(['tool', [['', 'allow']]]))).toBe(true)
    expect(hasBlankPattern(groups(['tool', [['a', 'allow']]]))).toBe(false)
  })
})
