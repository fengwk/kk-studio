import type { HarnessSessionEntryDTO, HarnessThreadInputDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/payload-json'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ThreadTimeline,
} from '@/features/ai/thread-timeline-types'
import { projectDurableEntry } from '@/features/ai/thread-timeline/entry-projection'
import { contentText } from '@/features/ai/thread-timeline/content-utils'

/**
 * Thread transcript projection:
 * durable path Entries are the sole transcript authority;
 * only QUEUED USER_MESSAGE / CUSTOM_MESSAGE inputs render as decoration overlays.
 */
export function buildThreadTimeline(
  entries: HarnessSessionEntryDTO[],
  inputs: HarnessThreadInputDTO[],
): ThreadTimeline {
  const messages: DialogueMessage[] = []
  const queuedMessages: QueuedThreadMessage[] = []
  const durableToolArguments = new Map<string, string[]>()
  let hasPendingInputs = false

  for (const entry of entries) {
    projectDurableEntry(entry, messages, durableToolArguments)
  }

  for (const input of inputs) {
    const inputType = input.inputType
    const inputStatus = input.status
    if (inputStatus !== 'QUEUED' || (inputType !== 'USER_MESSAGE' && inputType !== 'CUSTOM_MESSAGE')) {
      continue
    }
    const queuedMessage = extractQueuedMessage(input.payloadJson, inputType)
    if (!queuedMessage) {
      continue
    }
    queuedMessages.push({
      inputId: input.inputId,
      role: queuedMessage.role,
      text: queuedMessage.text,
      sequence: input.sequence,
    })
    hasPendingInputs = true
  }

  return {
    messages,
    queuedMessages,
    hasPendingInputs,
  }
}

function extractQueuedMessage(
  payloadJson: string,
  inputType: string,
): { role: 'user' | 'system'; text: string } | null {
  const payload = parsePayload(payloadJson)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  if (!text) {
    return null
  }
  const role = getString(message.role)
  if (inputType === 'CUSTOM_MESSAGE' && role === 'SYSTEM') {
    return { role: 'system', text }
  }
  return { role: 'user', text }
}
