import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  withSelectedOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDefinitionDTO, ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

function agent(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: null,
    environmentId: null,
    config: { toolIds: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function environment(
  name: string,
  skills: { name: string; description: string | null }[],
): EnvironmentCardDTO {
  return {
    id: `id-${name}`,
    name,
    rootPath: null,
    status: 'READY',
    ready: true,
    lastSeen: null,
    capabilities: [],
    skills,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
  }
}

function tool(
  id: string,
  name: string,
  description: string,
  version = '1',
): ToolCatalogEntryDTO {
  return {
    id,
    name,
    version,
    description,
  }
}

describe('agent-capability-candidates', () => {
  it('stores catalog IDs as values while displaying model-visible names', () => {
    const tools = [
      tool('base.bash', 'bash', 'shell'),
      tool('base.bash-v2', 'bash', 'duplicate'),
      tool('base.read', 'read', 'files'),
    ]

    expect(buildToolCandidates(tools)).toEqual([
      { value: 'base.bash', name: 'bash', version: '1', description: 'shell' },
      { value: 'base.bash-v2', name: 'bash', version: '1', description: 'duplicate' },
      { value: 'base.read', name: 'read', version: '1', description: 'files' },
    ])
    expect(buildToolCandidates(tools).map((item) => item.name)).toEqual(['bash', 'bash', 'read'])
    expect(buildToolCandidates(tools)[0]).not.toHaveProperty('source')
  })

  it('keeps permission candidates keyed by stable tool ID', () => {
    const tools = [
      tool('base.read', 'read', 'files'),
      tool('custom.read', 'read', 'custom files'),
      tool('base.read', 'renamed read', 'duplicate ID'),
    ]

    expect(buildPermissionToolCandidates(tools).map((item) => item.name)).toEqual([
      'base.read',
      'custom.read',
    ])
    expect(buildPermissionToolCandidates(tools)[0]).toEqual({
      value: 'base.read',
      name: 'base.read',
      version: '1',
      description: 'read — files',
    })
    expect(tools[0]).not.toHaveProperty('source')
  })

  it('never unions skills across two Environments: only the selected one is returned', () => {
    const local = environment('local', [
      { name: 'dev', description: 'dev skill' },
      { name: 'ops', description: 'ops skill' },
    ])
    const remote = environment('remote', [
      { name: 'dev', description: 'duplicate' },
      { name: 'docs', description: null },
    ])

    expect(buildSkillCandidates(local)).toEqual([
      { value: 'dev', name: 'dev', description: 'dev skill' },
      { value: 'ops', name: 'ops', description: 'ops skill' },
    ])
    // 切换来源后只返回该来源的 skills，绝不出现另一个 Environment 的 'ops'。
    expect(buildSkillCandidates(remote)).toEqual([
      { value: 'dev', name: 'dev', description: 'duplicate' },
      { value: 'docs', name: 'docs', description: null },
    ])
  })

  it('returns no live skills without a selected source', () => {
    expect(buildSkillCandidates(undefined)).toEqual([])
  })

  it('requires the ready flag and ignores stale READY status text', () => {
    const live = environment('local', [{ name: 'dev', description: 'dev skill' }])
    // 状态文本仍是 READY 但 ready=false（过期/不可用）：排除。
    expect(buildSkillCandidates({ ...live, ready: false })).toEqual([])
    // ready 标记为 true 时以可用性为准，状态文本只是展示。
    expect(
      buildSkillCandidates({ ...live, status: 'CONNECTING' }).map((item) => item.name),
    ).toEqual(['dev'])
  })

  it('retains selected orphans', () => {
    const local = environment('local', [{ name: 'dev', description: 'dev skill' }])
    expect(withSelectedOrphans(buildSkillCandidates(local), ['missing'])).toEqual([
      { value: 'dev', name: 'dev', description: 'dev skill' },
      { value: 'missing', name: 'missing', description: null, offline: true, missing: true },
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
})
