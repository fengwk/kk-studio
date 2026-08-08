import { describe, expect, it } from 'vitest'
import {
  buildSkillCandidates,
  buildToolCandidates,
  withSelectedOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

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

describe('agent-capability-candidates', () => {
  it('builds unified tools without exposing an Environment source', () => {
    const tools = buildToolCandidates([
      { name: 'bash', version: '1', description: 'shell', type: 'PLATFORM' },
      { name: 'bash', version: '2', description: 'duplicate', type: 'PLATFORM' },
      { name: 'read', version: null, description: 'files', type: 'ENVIRONMENT' },
    ])
    expect(tools).toEqual([
      { name: 'bash', version: '1', description: 'shell' },
      { name: 'read', version: null, description: 'files' },
    ])
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
})
