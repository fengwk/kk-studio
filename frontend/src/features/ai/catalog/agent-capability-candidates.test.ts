import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  withSelectedOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDefinitionDTO, SkillDTO, ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'

function agent(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: null,
    config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function skill(
  name: string,
  description: string | null = null,
  packageName = 'core',
  packageVersion = '1.0.0',
): SkillDTO {
  return {
    name,
    description: description ?? '',
    packageName,
    packageVersion,
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

  it('builds skill candidates from platform global skills with deduplication', () => {
    const skills = [
      skill('dev', 'dev skill'),
      skill('ops', 'ops skill'),
      skill('dev', 'duplicate exact'),
    ]

    expect(buildSkillCandidates(skills)).toEqual([
      {
        value: 'dev',
        name: 'dev',
        description: 'dev skill',
      },
      {
        value: 'ops',
        name: 'ops',
        description: 'ops skill',
      },
    ])
  })

  it('returns no skills for empty or undefined skills list', () => {
    expect(buildSkillCandidates(undefined)).toEqual([])
    expect(buildSkillCandidates(null)).toEqual([])
    expect(buildSkillCandidates([])).toEqual([])
  })

  it('retains selected orphans for global skills', () => {
    const candidates = buildSkillCandidates([
      skill('dev', 'dev skill'),
    ])

    const selected = ['dev', 'missing-skill']

    const merged = withSelectedOrphans(candidates, selected)
    expect(merged).toEqual([
      {
        value: 'dev',
        name: 'dev',
        description: 'dev skill',
      },
      {
        value: 'missing-skill',
        name: 'missing-skill',
        description: null,
        offline: true,
        missing: true,
      },
    ])
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

  it('retains selected orphans for tools/subagents without overwriting available candidates', () => {
    const candidates = [
      { value: 'bash', name: 'bash', description: 'shell' },
      { value: 'read', name: 'read', description: null },
    ]

    expect(withSelectedOrphans(candidates, ['bash', 'missing', '  '])).toEqual([
      { value: 'bash', name: 'bash', description: 'shell' },
      { value: 'read', name: 'read', description: null },
      {
        value: 'missing',
        name: 'missing',
        description: null,
        offline: true,
        missing: true,
      },
    ])
  })
})
