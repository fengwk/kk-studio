import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  filterToolsForEnvironment,
  isSameSkillRef,
  isToolCompatibleWithEnvironment,
  toggleSkillRef,
  withSelectedOrphans,
  withSelectedSkillOrphans,
  withSelectedToolOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDefinitionDTO, ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentSkillDTO } from '@/shared/api/contracts/ai-environment'

function agent(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: null,
    environmentId: null,
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function inventorySkill(
  sourceId: string,
  name: string,
  description: string | null = null,
): EnvironmentSkillDTO {
  return {
    sourceId,
    name,
    description: description ?? '',
    sourceVersion: '1',
    baseDirectory: '/tmp',
    contentRevision: 'sha256-abc',
    discoveredAt: '2026-07-20T00:00:00.000Z',
  }
}

function tool(
  name: string,
  description: string,
  environmentRequired = false,
  environmentId: string | null = null,
): ToolCatalogEntryDTO {
  return {
    name,
    description,
    environmentRequired,
    environmentId,
  }
}

describe('agent-capability-candidates', () => {
  it('stores tool names as values and names', () => {
    const tools = [
      tool('bash', 'shell'),
      tool('bash', 'duplicate'),
      tool('read', 'files'),
    ]

    expect(buildToolCandidates(tools)).toEqual([
      { value: 'bash', name: 'bash', description: 'shell' },
      { value: 'read', name: 'read', description: 'files' },
    ])
    expect(buildToolCandidates(tools).map((item) => item.name)).toEqual(['bash', 'read'])
    expect(buildToolCandidates(tools)[0]).not.toHaveProperty('source')
  })

  it('keeps permission candidates keyed by tool name', () => {
    const tools = [
      tool('read', 'files'),
      tool('custom_read', 'custom files'),
      tool('read', 'duplicate name'),
    ]

    expect(buildPermissionToolCandidates(tools).map((item) => item.name)).toEqual([
      'read',
      'custom_read',
    ])
    expect(buildPermissionToolCandidates(tools)[0]).toEqual({
      value: 'read',
      name: 'read',
      description: 'files',
    })
    expect(tools[0]).not.toHaveProperty('source')
  })

  it('builds skill candidates from durable inventory without gating on environment ready', () => {
    const inventory = [
      inventorySkill('src-1', 'dev', 'dev skill'),
      inventorySkill('src-1', 'ops', 'ops skill'),
      inventorySkill('src-1', 'dev', 'duplicate exact'),
    ]

    expect(buildSkillCandidates(inventory)).toEqual([
      {
        ref: { sourceId: 'src-1', name: 'dev' },
        sourceId: 'src-1',
        name: 'dev',
        description: 'dev skill',
      },
      {
        ref: { sourceId: 'src-1', name: 'ops' },
        sourceId: 'src-1',
        name: 'ops',
        description: 'ops skill',
      },
    ])
  })

  it('preserves same-name skills from different sourceIds as distinct candidates', () => {
    const inventory = [
      inventorySkill('src-1', 'search', 'source 1 search'),
      inventorySkill('src-2', 'search', 'source 2 search'),
    ]

    expect(buildSkillCandidates(inventory)).toEqual([
      {
        ref: { sourceId: 'src-1', name: 'search' },
        sourceId: 'src-1',
        name: 'search',
        description: 'source 1 search',
      },
      {
        ref: { sourceId: 'src-2', name: 'search' },
        sourceId: 'src-2',
        name: 'search',
        description: 'source 2 search',
      },
    ])
  })

  it('returns no skills for empty or undefined inventory', () => {
    expect(buildSkillCandidates(undefined)).toEqual([])
    expect(buildSkillCandidates(null)).toEqual([])
    expect(buildSkillCandidates([])).toEqual([])
  })

  it('retains selected orphans without collapsing same-name refs from different sources', () => {
    const candidates = buildSkillCandidates([
      inventorySkill('src-1', 'dev', 'dev skill'),
    ])

    const selected = [
      { sourceId: 'src-1', name: 'dev' },
      { sourceId: 'src-2', name: 'dev' },
      { sourceId: 'src-3', name: 'missing-skill' },
    ]

    const merged = withSelectedSkillOrphans(candidates, selected)
    expect(merged).toEqual([
      {
        ref: { sourceId: 'src-1', name: 'dev' },
        sourceId: 'src-1',
        name: 'dev',
        description: 'dev skill',
      },
      {
        ref: { sourceId: 'src-2', name: 'dev' },
        sourceId: 'src-2',
        name: 'dev',
        description: null,
        missing: true,
      },
      {
        ref: { sourceId: 'src-3', name: 'missing-skill' },
        sourceId: 'src-3',
        name: 'missing-skill',
        description: null,
        missing: true,
      },
    ])
  })

  it('compares and toggles skill refs by exact composite identity', () => {
    const refA = { sourceId: 'src-1', name: 'dev' }
    const refB = { sourceId: 'src-2', name: 'dev' }
    const refC = { sourceId: 'src-1', name: 'ops' }

    expect(isSameSkillRef(refA, { sourceId: ' src-1 ', name: ' dev ' })).toBe(true)
    expect(isSameSkillRef(refA, refB)).toBe(false)
    expect(isSameSkillRef(refA, refC)).toBe(false)

    const initial = [refA]
    const added = toggleSkillRef(initial, refB)
    expect(added).toEqual([refA, refB])

    const removedA = toggleSkillRef(added, { sourceId: 'src-1', name: 'dev' })
    expect(removedA).toEqual([refB])
  })

  it('builds subagent candidates from the global Agent catalog with name and description', () => {
    expect(
      buildSubagentCandidates([
        agent('helper', 'runs isolated tasks'),
        agent('writer', null),
        agent('helper', 'duplicate'),
        agent('  ', 'blank name'),
      ]),
    ).toEqual([
      { value: 'helper', name: 'helper', description: 'runs isolated tasks' },
      { value: 'writer', name: 'writer', description: null },
    ])
  })

  it('returns no subagent candidates from an empty catalog', () => {
    expect(buildSubagentCandidates([])).toEqual([])
  })

  it('keeps selected subagents missing from the catalog as removable orphans', () => {
    const candidates = buildSubagentCandidates([agent('helper', 'runs isolated tasks')])
    expect(withSelectedOrphans(candidates, ['ghost-agent'])).toEqual([
      { value: 'helper', name: 'helper', description: 'runs isolated tasks' },
      {
        value: 'ghost-agent',
        name: 'ghost-agent',
        description: null,
        offline: true,
        missing: true,
      },
    ])
  })

  /**
   * 测试意图：验证工具目录项与环境绑定的兼容性判定规则。
   * 1. 非环境工具（environmentRequired=false）无论是否绑定环境均可用；
   * 2. 要求环境的工具在未绑定环境时不可用；
   * 3. 通用环境工具（environmentRequired=true 且 environmentId=null）在绑定任意非空环境时可用；
   * 4. 精确环境工具仅在绑定环境 ID 完全相符时可用。
   */
  it('determines tool compatibility based on environment requirements and selected environment', () => {
    const hostTool = tool('bash', 'shell', false, null)
    const genericEnvTool = tool('lsp', 'lsp tool', true, null)
    const exactToolA = tool('fs-a', 'fs tool', true, 'env-A')

    // 非环境工具始终可用
    expect(isToolCompatibleWithEnvironment(hostTool, null)).toBe(true)
    expect(isToolCompatibleWithEnvironment(hostTool, '')).toBe(true)
    expect(isToolCompatibleWithEnvironment(hostTool, 'env-A')).toBe(true)
    expect(isToolCompatibleWithEnvironment(hostTool, 'env-B')).toBe(true)

    // 要求环境的工具在未选环境时不可用
    expect(isToolCompatibleWithEnvironment(genericEnvTool, null)).toBe(false)
    expect(isToolCompatibleWithEnvironment(genericEnvTool, '')).toBe(false)
    expect(isToolCompatibleWithEnvironment(genericEnvTool, '   ')).toBe(false)
    expect(isToolCompatibleWithEnvironment(exactToolA, null)).toBe(false)
    expect(isToolCompatibleWithEnvironment(exactToolA, '')).toBe(false)

    // 通用环境工具在选定任意非空环境时可用
    expect(isToolCompatibleWithEnvironment(genericEnvTool, 'env-A')).toBe(true)
    expect(isToolCompatibleWithEnvironment(genericEnvTool, 'env-B')).toBe(true)

    // 精确环境工具仅在选定环境 ID 匹配时可用
    expect(isToolCompatibleWithEnvironment(exactToolA, 'env-A')).toBe(true)
    expect(isToolCompatibleWithEnvironment(exactToolA, 'env-B')).toBe(false)
  })

  /**
   * 测试意图：验证 buildToolCandidates 依据 environmentId 进行候选工具过滤。
   * - 未指定环境时隐藏所有要求环境的工具；
   * - 指定环境时仅展示非环境工具、通用环境工具和属于该环境的精确工具。
   */
  it('filters tool candidates based on specified environmentId', () => {
    const tools = [
      tool('bash', 'host tool', false, null),
      tool('generic-tool', 'generic env tool', true, null),
      tool('tool-a', 'env A tool', true, 'env-A'),
      tool('tool-b', 'env B tool', true, 'env-B'),
    ]

    // 未绑定环境（null 或 ''）
    const unboundCandidates = buildToolCandidates(tools, null)
    expect(unboundCandidates.map((t) => t.value)).toEqual(['bash'])

    // 绑定环境 env-A
    const envACandidates = buildToolCandidates(tools, 'env-A')
    expect(envACandidates.map((t) => t.value)).toEqual(['bash', 'generic-tool', 'tool-a'])

    // 绑定环境 env-B
    const envBCandidates = buildToolCandidates(tools, 'env-B')
    expect(envBCandidates.map((t) => t.value)).toEqual(['bash', 'generic-tool', 'tool-b'])

    // 未提供 environmentId 参数时保留全部工具（兼容既有未过滤行为）
    const allCandidates = buildToolCandidates(tools)
    expect(allCandidates.map((t) => t.value)).toEqual([
      'bash',
      'generic-tool',
      'tool-a',
      'tool-b',
    ])
  })

  /**
   * 测试意图：验证 withSelectedToolOrphans 仅将真正未知的工具展示为 orphan，
   * 属于已知 catalog 但因环境不兼容而被过滤的已知环境工具绝不重新作为 orphan 展示。
   */
  it('preserves genuinely unknown tool names as orphans while excluding known incompatible tools', () => {
    const catalog = [
      tool('bash', 'host tool', false, null),
      tool('generic-tool', 'generic env tool', true, null),
      tool('tool-b', 'env B tool', true, 'env-B'),
    ]

    // 假设当前环境为 null，仅 bash 为合法候选
    const compatibleCandidates = buildToolCandidates(catalog, null)
    expect(compatibleCandidates.map((c) => c.value)).toEqual(['bash'])

    // 已选列表中既有宿主工具、已知不兼容环境工具，也有未知的 orphan 工具
    const selected = ['bash', 'generic-tool', 'tool-b', 'unknown.orphan']
    const merged = withSelectedToolOrphans(compatibleCandidates, selected, catalog)

    // generic-tool 和 tool-b 属于已知 catalog，但因环境不符被隐藏，绝不应作为 orphan 重新展示
    // unknown.orphan 真正缺失，应作为 orphan 置灰展示
    expect(merged).toEqual([
      { value: 'bash', name: 'bash', description: 'host tool' },
      {
        value: 'unknown.orphan',
        name: 'unknown.orphan',
        description: null,
        offline: true,
        missing: true,
      },
    ])
  })

  /**
   * 测试意图：验证 filterToolsForEnvironment 在环境切换或解绑时清理不兼容工具。
   * - 解绑时清除所有环境工具，保留宿主工具与未知工具；
   * - 跨环境切换时清理旧环境专属精确工具，保留宿主工具、通用环境工具、新环境专属精确工具与未知工具。
   */
  it('filters selected tools when environment changes or unbinds', () => {
    const catalog = [
      tool('bash', 'host tool', false, null),
      tool('generic-tool', 'generic env tool', true, null),
      tool('tool-a', 'env A tool', true, 'env-A'),
      tool('tool-b', 'env B tool', true, 'env-B'),
    ]

    const selected = [
      'bash',
      'generic-tool',
      'tool-a',
      'unknown.orphan',
    ]

    // 切换到 env-B：tool-a 被移除，保留 bash、generic-tool 与 unknown.orphan
    const switchedToB = filterToolsForEnvironment(selected, catalog, 'env-B')
    expect(switchedToB).toEqual(['bash', 'generic-tool', 'unknown.orphan'])

    // 解绑环境（切换到 '' 或 null）：所有要求环境的已知工具均被移除，保留 bash 与 unknown.orphan
    const unbound = filterToolsForEnvironment(selected, catalog, '')
    expect(unbound).toEqual(['bash', 'unknown.orphan'])

    const unboundNull = filterToolsForEnvironment(selected, catalog, null)
    expect(unboundNull).toEqual(['bash', 'unknown.orphan'])
  })
})
