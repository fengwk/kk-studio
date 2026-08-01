import { describe, expect, it } from 'vitest'
import {
  buildSkillCandidates,
  buildToolCandidates,
  withSelectedOrphans,
} from '@/features/ai/catalog/agent-capability-candidates'

describe('agent-capability-candidates', () => {
  it('builds unified tools without exposing an Environment source', () => {
    const tools = buildToolCandidates([
      { name: 'bash', version: '1', description: 'shell' },
      { name: 'bash', version: '2', description: 'duplicate' },
      { name: 'read', version: null, description: 'files' },
    ])
    expect(tools).toEqual([
      { name: 'bash', version: '1', description: 'shell' },
      { name: 'read', version: null, description: 'files' },
    ])
    expect(tools[0]).not.toHaveProperty('source')
  })

  it('merges skills from READY live Environments and retains selected orphans', () => {
    const environments = [
      {
        name: 'local',
        status: 'READY',
        lastSeen: null,
        tools: [],
        skills: [
          { name: 'dev', description: 'dev skill' },
          { name: 'ops', description: 'ops skill' },
        ],
      },
      {
        name: 'remote',
        status: 'READY',
        lastSeen: null,
        tools: [],
        skills: [{ name: 'dev', description: 'duplicate' }, { name: 'docs', description: null }],
      },
    ]
    expect(buildSkillCandidates(environments).map((item) => item.name)).toEqual([
      'dev',
      'ops',
      'docs',
    ])
    expect(buildSkillCandidates([{ ...environments[0], status: 'CONNECTING' }])).toEqual([])
    expect(withSelectedOrphans(buildSkillCandidates(environments), ['missing'])).toEqual([
      { name: 'dev', description: 'dev skill' },
      { name: 'ops', description: 'ops skill' },
      { name: 'docs', description: null },
      { name: 'missing', description: null, offline: true, missing: true },
    ])
  })
})
