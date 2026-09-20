import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  withSelectedOrphans,
  withSelectedSkillOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type {
  AgentDefinitionDTO,
  SkillPackageDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'

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

function skillPackage(
  packageName: string,
  skills: { name: string; description: string }[],
): SkillPackageDTO {
  return {
    packageName,
    description: null,
    repositoryUrl: 'https://example.com/repo.git',
    branch: 'main',
    currentCommit: '1111111111111111111111111111111111111111',
    observedHeadCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    checkStatus: 'UP_TO_DATE',
    skills,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
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

  it('builds skill candidates from skill packages formatted as package / name', () => {
    const packages = [
      skillPackage('core-tools', [
        { name: 'dev', description: 'dev skill' },
        { name: 'ops', description: 'ops skill' },
        { name: 'dev', description: 'duplicate in same package' },
      ]),
      skillPackage('extra-tools', [
        { name: 'web', description: 'web skill' },
      ]),
    ]

    expect(buildSkillCandidates(packages)).toEqual([
      {
        value: 'core-tools:dev',
        name: 'core-tools / dev',
        description: 'dev skill',
      },
      {
        value: 'core-tools:ops',
        name: 'core-tools / ops',
        description: 'ops skill',
      },
      {
        value: 'extra-tools:web',
        name: 'extra-tools / web',
        description: 'web skill',
      },
    ])
  })

  it('returns no skills for empty or undefined skills list', () => {
    expect(buildSkillCandidates(undefined)).toEqual([])
    expect(buildSkillCandidates(null)).toEqual([])
    expect(buildSkillCandidates([])).toEqual([])
  })

  it('retains selected orphans for skills with package / name formatting', () => {
    const candidates = buildSkillCandidates([
      skillPackage('core-tools', [{ name: 'dev', description: 'dev skill' }]),
    ])

    const selected = [
      { packageName: 'core-tools', name: 'dev' },
      { packageName: 'old-pkg', name: 'legacy-skill' },
    ]

    const merged = withSelectedSkillOrphans(candidates, selected)
    expect(merged).toEqual([
      {
        value: 'core-tools:dev',
        name: 'core-tools / dev',
        description: 'dev skill',
      },
      {
        value: 'old-pkg:legacy-skill',
        name: 'old-pkg / legacy-skill',
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
