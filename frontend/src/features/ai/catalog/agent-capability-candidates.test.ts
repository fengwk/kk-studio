import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  isSameSkillRef,
  toggleSkillRef,
  withSelectedOrphans,
  withSelectedSkillOrphans,
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
   * 测试意图：验证 buildToolCandidates 不再接收环境 ID，永远返回全部有效工具并去重；
   * 结合 withSelectedOrphans 能正确保留未在 catalog 中的已选工具作为 missing orphan。
   */
  it('builds full tool candidates without environment filtering and preserves unknown tools as orphans', () => {
    const catalog = [
      tool('bash', 'host tool', false, null),
      tool('generic-tool', 'generic env tool', true, null),
      tool('tool-b', 'env B tool', true, 'env-B'),
    ]

    const candidates = buildToolCandidates(catalog)
    expect(candidates.map((c) => c.value)).toEqual(['bash', 'generic-tool', 'tool-b'])

    const selected = ['bash', 'tool-b', 'unknown.orphan']
    const merged = withSelectedOrphans(candidates, selected)

    expect(merged).toEqual([
      { value: 'bash', name: 'bash', description: 'host tool' },
      { value: 'generic-tool', name: 'generic-tool', description: 'generic env tool' },
      { value: 'tool-b', name: 'tool-b', description: 'env B tool' },
      {
        value: 'unknown.orphan',
        name: 'unknown.orphan',
        description: null,
        offline: true,
        missing: true,
      },
    ])
  })
})
