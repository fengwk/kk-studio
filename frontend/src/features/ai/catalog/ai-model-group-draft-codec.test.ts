import { describe, expect, it } from 'vitest'
import type { ModelGroupDraft } from '@/features/ai/catalog/ai-console-types'
import {
  emptyModelGroupDraft,
  groupEditablePayload,
  newGroupMemberDraft,
  toModelGroupDraft,
} from '@/features/ai/catalog/ai-model-group-draft-codec'
import type { AgentModelDTO } from '@/shared/api/contracts/ai-catalog'

function groupDraft(overrides: Partial<ModelGroupDraft> = {}): ModelGroupDraft {
  return {
    ...emptyModelGroupDraft(),
    name: ' coding ',
    description: ' fallback chain ',
    members: [
      newGroupMemberDraft({ modelName: ' primary ', variant: ' high ' }),
      newGroupMemberDraft({ modelName: 'backup', variant: 'fast' }),
    ],
    ...overrides,
  }
}

function groupModel(): AgentModelDTO {
  return {
    name: 'coding',
    kind: 'GROUP',
    description: 'fallback chain',
    group: {
      members: [
        { modelName: 'primary', variant: 'high' },
        { modelName: 'backup', variant: 'fast' },
      ],
      fallbackStatusCodes: [429, 503],
      cooldownSeconds: 0,
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('ai-model-group-draft-codec', () => {
  it('uses the documented defaults and keeps member order', () => {
    const empty = emptyModelGroupDraft()
    expect(empty.fallbackStatusCodes).toEqual([429])
    expect(empty.cooldownSeconds).toBe('0')
    expect(empty.members).toHaveLength(1)

    const payload = groupEditablePayload(groupDraft())
    expect(payload).toEqual({
      name: 'coding',
      kind: 'GROUP',
      description: 'fallback chain',
      group: {
        members: [
          { modelName: 'primary', variant: 'high' },
          { modelName: 'backup', variant: 'fast' },
        ],
        fallbackStatusCodes: [429],
        cooldownSeconds: 0,
      },
    })
    expect(payload).not.toHaveProperty('providerName')
    expect(payload).not.toHaveProperty('config')
  })

  it('accepts cooldown 0 and preserves the explicit zero', () => {
    const payload = groupEditablePayload(groupDraft({
      fallbackStatusCodes: [400, 599],
      cooldownSeconds: '0',
    }))
    expect(payload.group?.cooldownSeconds).toBe(0)
    expect(payload.group?.fallbackStatusCodes).toEqual([400, 599])
  })

  it.each([
    ['empty member list', { members: [] }],
    ['blank model name', { members: [newGroupMemberDraft({ modelName: '  ', variant: 'high' })] }],
    ['blank variant', { members: [newGroupMemberDraft({ modelName: 'primary', variant: ' ' })] }],
    ['duplicate member', {
      members: [
        newGroupMemberDraft({ modelName: 'primary', variant: 'high' }),
        newGroupMemberDraft({ modelName: ' primary ', variant: 'high' }),
      ],
    }],
    ['out-of-range status code', { fallbackStatusCodes: [200] }],
    ['duplicate status code', { fallbackStatusCodes: [429, 429] }],
    ['negative cooldown', { cooldownSeconds: '-1' }],
    ['fractional cooldown', { cooldownSeconds: '1.5' }],
  ] as Array<[string, Partial<ModelGroupDraft>]>)('rejects %s', (_label, overrides) => {
    expect(() => groupEditablePayload(groupDraft(overrides))).toThrow()
  })

  it('reads a group DTO without fabricating a group-level variant or price', () => {
    const draft = toModelGroupDraft(groupModel())
    expect(draft).toMatchObject({
      name: 'coding',
      description: 'fallback chain',
      fallbackStatusCodes: [429, 503],
      cooldownSeconds: '0',
    })
    expect(draft.members.map((member) => [member.modelName, member.variant])).toEqual([
      ['primary', 'high'],
      ['backup', 'fast'],
    ])
  })
})
