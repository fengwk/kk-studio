import { describe, expect, it } from 'vitest'
import {
  buildPermissionToolCandidates,
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  withSelectedOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDefinitionDTO, ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

function agent(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: null,
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function environment(name: string, skills: { name: string; description: string | null }[]): LiveEnvironmentDTO {
  return {
    name,
    status: 'READY',
    ready: true,
    lastSeen: null,
    tools: [],
    skills,
    mcpServers: [],
  }
}

function tool(
  id: string,
  name: string,
  description: string | null,
  version: string | null = '1',
  backend: ToolCatalogEntryDTO['backend'] = 'HOST',
  type: ToolCatalogEntryDTO['type'] = 'PLATFORM',
): ToolCatalogEntryDTO {
  return {
    id,
    name,
    version,
    description,
    backend,
    type,
  }
}

describe('agent-capability-candidates', () => {
  it('keeps Agent candidates keyed by model-visible name', () => {
    const tools = [
      tool('base.bash', 'bash', 'shell'),
      tool('base.bash-v2', 'bash', 'duplicate'),
      tool('base.read', 'read', 'files', null, 'ENVIRONMENT_CAPABILITY', 'ENVIRONMENT'),
    ]

    expect(buildToolCandidates(tools)).toEqual([
      { name: 'bash', version: '1', description: 'shell' },
      { name: 'read', version: null, description: 'files' },
    ])
    expect(buildToolCandidates(tools).map((item) => item.name)).toEqual(['bash', 'read'])
    expect(buildToolCandidates(tools)[0]).not.toHaveProperty('source')
  })

  it('keeps permission candidates keyed by stable tool ID', () => {
    const tools = [
      tool('base.read', 'read', 'files', null, 'ENVIRONMENT_CAPABILITY', 'ENVIRONMENT'),
      tool('plugin.read', 'read', 'plugin files', '1', 'PLUGIN'),
      tool('base.read', 'renamed read', 'duplicate ID'),
    ]

    expect(buildPermissionToolCandidates(tools).map((item) => item.name)).toEqual([
      'base.read',
      'plugin.read',
    ])
    expect(buildPermissionToolCandidates(tools)[0]).toEqual({
      name: 'base.read',
      version: null,
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

    expect(buildSkillCandidates(local).map((item) => item.name)).toEqual(['dev', 'ops'])
    // 切换来源后只返回该来源的 skills，绝不出现另一个 Environment 的 'ops'。
    expect(buildSkillCandidates(remote).map((item) => item.name)).toEqual(['dev', 'docs'])
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
      { name: 'dev', description: 'dev skill' },
      { name: 'missing', description: null, offline: true, missing: true },
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
      { name: 'helper', description: 'runs isolated tasks' },
      { name: 'writer', description: null },
    ])
  })

  it('returns no subagent candidates from an empty catalog', () => {
    expect(buildSubagentCandidates([])).toEqual([])
  })

  it('keeps selected subagents missing from the catalog as removable orphans', () => {
    const candidates = buildSubagentCandidates([agent('helper', 'runs isolated tasks')])
    expect(withSelectedOrphans(candidates, ['ghost-agent'])).toEqual([
      { name: 'helper', description: 'runs isolated tasks' },
      { name: 'ghost-agent', description: null, offline: true, missing: true },
    ])
  })
})
