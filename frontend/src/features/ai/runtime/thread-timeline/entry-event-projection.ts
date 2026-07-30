import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList } from '@/features/ai/runtime/payload-json'
import type {
  EntryEventDialogueMessage,
  EntryEventKind,
} from '@/features/ai/runtime/thread-timeline-types'

export function projectRootEntry(entry: HarnessSessionEntryDTO): EntryEventDialogueMessage {
  return event(entry, 'root', '会话开始', '已创建会话树根节点。')
}

export function projectRuntimeConfigEntry(
  entry: HarnessSessionEntryDTO,
  payload: Record<string, unknown>,
): EntryEventDialogueMessage {
  const agent = asRecord(payload.agent)
  const model = asRecord(payload.model)
  const descriptor = asRecord(model.descriptor)
  const variant = asRecord(model.variant)
  const agentName = display(agent.name, '未知 Agent')
  const agentId = display(agent.definitionId, '')
  const provider = display(descriptor.providerType, 'unknown').toLowerCase()
  const modelId = display(descriptor.modelId, 'unknown-model')
  const variantId = display(variant.id, 'default')
  const yolo =
    payload.yoloEnabled === true ? '开启' : payload.yoloEnabled === false ? '关闭' : '未知'
  const tools = names(payload.tools)
  const skills = names(payload.skills)
  const lines = [
    `Agent：${agentName}${agentId ? ` (#${agentId})` : ''}`,
    `模型：${provider}/${modelId} · ${variantId}`,
    `YOLO：${yolo}`,
  ]
  if (tools.length > 0) {
    lines.push(`工具：${tools.join('、')}`)
  }
  if (skills.length > 0) {
    lines.push(`技能：${skills.join('、')}`)
  }
  return event(entry, 'runtime_config', '运行配置已记录', lines.join('\n'))
}

export function projectEmptyMessageEntry(
  entry: HarnessSessionEntryDTO,
  role: string,
): EntryEventDialogueMessage {
  return event(
    entry,
    'empty_message',
    `${role || '未知角色'} 消息`,
    '该消息 Entry 没有可展示的文本、思考、工具调用或工具结果。',
  )
}

export function projectUnsupportedMessageEntry(
  entry: HarnessSessionEntryDTO,
  role: string,
): EntryEventDialogueMessage {
  return event(
    entry,
    'unsupported_message',
    '无法识别消息 Entry',
    role
      ? `暂不支持的消息角色：${role}。原始 payload 可展开查看。`
      : '消息角色或 payload 无效。原始 payload 可展开查看。',
  )
}

export function projectUnknownEntry(entry: HarnessSessionEntryDTO): EntryEventDialogueMessage {
  return event(
    entry,
    'unknown_entry',
    `未识别 Entry：${entry.entryType || '未知类型'}`,
    '该 Entry 类型尚无专用渲染器，原始 payload 可展开查看。',
  )
}

function event(
  entry: HarnessSessionEntryDTO,
  kind: EntryEventKind,
  title: string,
  text: string,
): EntryEventDialogueMessage {
  return {
    id: `entry:${entry.entryId}`,
    role: 'entry',
    kind,
    title,
    text,
    rawPayloadJson: entry.payloadJson || '{}',
    subjectEntryId: entry.entryId,
    createdAt: entry.createTime,
    status: 'done',
  }
}

function names(value: unknown): string[] {
  return getRecordList(value)
    .map((item) => {
      const descriptor = asRecord(item.descriptor)
      return display(descriptor.name, '') || display(item.name, '')
    })
    .filter(Boolean)
}

function display(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) {
    return value.trim()
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value)
  }
  return fallback
}
