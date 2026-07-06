import { asRecord, getInteger, getRecordList, getString, getToolContentType } from '@/features/ai/session-event-payload'
import type { ToolAttachment } from '@/features/ai/session-event-types'
import type { ToolProjectionState } from '@/features/ai/session-timeline-tool-state'

export function applyToolContentDeltas(state: ToolProjectionState, payload: Record<string, unknown>) {
  for (const indexedDelta of getRecordList(payload.contentDeltas)) {
    const slotIndex = getInteger(indexedDelta.index)
    if (slotIndex === null) {
      continue
    }

    const contentDelta = asRecord(indexedDelta.contentDelta)
    const contentType = getToolContentType(contentDelta.type)
    if (!contentType) {
      continue
    }

    const slot = state.outputSlots.get(slotIndex) ?? { text: '' }
    slot.type = contentType
    if (contentType === 'text') {
      slot.text += getString(contentDelta.text)
      slot.attachment = undefined
    } else {
      slot.attachment = {
        type: contentType,
        name: getString(contentDelta.name),
        mime: getString(contentDelta.mime),
        data: getString(contentDelta.data),
      }
    }
    state.outputSlots.set(slotIndex, slot)
  }

  syncToolMessage(state)
}

export function syncToolMessage(state: ToolProjectionState) {
  const orderedSlots = Array.from(state.outputSlots.entries())
    .sort(([left], [right]) => left - right)
    .map(([, slot]) => slot)

  const textBlocks: string[] = []
  const attachments: ToolAttachment[] = []
  for (const slot of orderedSlots) {
    if (slot.type === 'text') {
      if (slot.text) {
        textBlocks.push(slot.text)
      }
      continue
    }
    if (slot.attachment?.data) {
      attachments.push(slot.attachment)
    }
  }

  state.message.text = textBlocks.join('\n\n')
  state.message.attachments = attachments
}
