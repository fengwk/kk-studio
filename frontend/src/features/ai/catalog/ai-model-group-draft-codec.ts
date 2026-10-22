import type { ModelGroupDraft, ModelGroupMemberDraft } from '@/features/ai/catalog/ai-console-types'
import {
  type AgentModelDTO,
  type AgentModelCreateDTO,
  type AgentModelEditablePropertiesDTO,
} from '@/shared/api/contracts/ai-catalog'
import { trimToNull } from '@/features/ai/catalog/ai-resource-draft-primitives'

let memberDraftIdSeed = 0

export function newGroupMemberDraft(input?: Partial<Omit<ModelGroupMemberDraft, 'draftId'>>): ModelGroupMemberDraft {
  memberDraftIdSeed += 1
  return {
    draftId: `group-member-${memberDraftIdSeed}`,
    modelName: input?.modelName ?? '',
    variant: input?.variant ?? '',
  }
}

export function emptyModelGroupDraft(): ModelGroupDraft {
  return {
    name: '',
    description: '',
    members: [newGroupMemberDraft()],
    fallbackStatusCodes: [429],
    cooldownSeconds: '0',
  }
}

export function toModelGroupDraft(model: AgentModelDTO): ModelGroupDraft {
  const group = model.group
  if (!group) {
    throw new Error(`GROUP ${model.name} is missing group config`)
  }
  return {
    name: model.name,
    description: model.description || '',
    members: group.members.map((member) => newGroupMemberDraft(member)),
    fallbackStatusCodes: [...group.fallbackStatusCodes],
    cooldownSeconds: String(group.cooldownSeconds),
  }
}

function cooldownToNumber(value: string): number {
  const normalized = value.trim()
  if (!/^(?:0|[1-9]\d*)$/.test(normalized)) {
    throw new Error('cooldownSeconds must be a non-negative integer')
  }
  const parsed = Number(normalized)
  if (!Number.isSafeInteger(parsed)) {
    throw new Error('cooldownSeconds must be a safe integer')
  }
  return parsed
}

function statusCodes(value: (number | string)[]): number[] {
  const result: number[] = []
  for (const item of value) {
    if (typeof item !== 'number' || !Number.isInteger(item) || item < 400 || item > 599) {
      throw new Error('fallbackStatusCodes must contain integers between 400 and 599')
    }
    result.push(item)
  }
  if (new Set(result).size !== result.length) {
    throw new Error('fallbackStatusCodes must not contain duplicates')
  }
  return result
}

/** 组请求只发送 group 配置；绝不附带派生 config 或单模型字段。 */
export function groupEditablePayload(
  draft: ModelGroupDraft,
): Pick<AgentModelEditablePropertiesDTO, 'name' | 'kind' | 'description' | 'group'> {
  const members = draft.members.map((member) => ({
    modelName: member.modelName.trim(),
    variant: member.variant.trim(),
  }))
  if (members.length === 0) {
    throw new Error('group.members must contain at least one member')
  }
  const seen = new Set<string>()
  for (const [index, member] of members.entries()) {
    if (!member.modelName) {
      throw new Error(`group.members[${index}].modelName is required`)
    }
    if (!member.variant) {
      throw new Error(`group.members[${index}].variant is required`)
    }
    const key = `${member.modelName}\u0000${member.variant}`
    if (seen.has(key)) {
      throw new Error(`duplicate group member: ${member.modelName} (${member.variant})`)
    }
    seen.add(key)
  }
  return {
    name: (() => {
      const name = trimToNull(draft.name)
      if (!name) {
        throw new Error('name must not be blank')
      }
      return name
    })(),
    kind: 'GROUP',
    description: trimToNull(draft.description),
    group: {
      members,
      fallbackStatusCodes: statusCodes(draft.fallbackStatusCodes),
      cooldownSeconds: cooldownToNumber(draft.cooldownSeconds),
    },
  }
}

export function toEditableModelGroup(draft: ModelGroupDraft): AgentModelCreateDTO {
  return groupEditablePayload(draft)
}

export function toEditableModelGroupUpdate(draft: ModelGroupDraft): AgentModelEditablePropertiesDTO {
  return groupEditablePayload(draft)
}
