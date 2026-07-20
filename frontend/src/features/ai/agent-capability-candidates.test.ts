import { describe, expect, it } from 'vitest'
import {
  buildCapabilityCandidates,
  markInvalidSelections,
} from '@/features/ai/agent-capability-candidates'

describe('agent-capability-candidates', () => {
  it('prefers platform tools/skills and marks offline/invalid selections', () => {
    const environments = [
      {
        name: 'platform',
        status: 'READY',
        lastSeen: null,
        tools: [{ name: 'bash', version: '1', description: 'shell' }],
        skills: [{ name: 'dev', description: 'dev skill' }],
      },
      {
        name: 'local',
        status: 'DISCONNECTED',
        lastSeen: null,
        tools: [
          { name: 'bash', version: '2', description: 'shadowed' },
          { name: 'lsp', version: '1', description: 'language' },
        ],
        skills: [{ name: 'ops', description: 'ops skill' }],
      },
    ]
    const tools = buildCapabilityCandidates(environments, 'local', 'tools')
    expect(tools.map((item) => item.name)).toEqual(['bash', 'lsp'])
    expect(tools.find((item) => item.name === 'bash')?.source).toBe('platform')
    expect(tools.find((item) => item.name === 'lsp')?.offline).toBe(true)

    const marks = markInvalidSelections(['bash', 'missing', 'lsp'], tools)
    expect(marks).toEqual([
      { name: 'bash', invalid: false, offline: false },
      { name: 'missing', invalid: true, offline: false },
      { name: 'lsp', invalid: false, offline: true },
    ])

    // no selected environment and non-ready platform should yield empty candidates
    expect(buildCapabilityCandidates([{ ...environments[0], status: 'DISCONNECTED' }], '', 'skills')).toEqual([])
    expect(buildCapabilityCandidates(environments, '  ', 'skills').map((item) => item.name)).toEqual(['dev'])
  })
})
