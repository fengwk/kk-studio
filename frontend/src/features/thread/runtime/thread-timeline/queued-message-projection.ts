import { asRecord, getRecordList } from '@/features/thread/runtime/thread-timeline/payload-json'
import { contentText, toDialogueContents } from '@/features/thread/runtime/thread-timeline/content-utils'
import type { DialogueContent } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

export function extractQueuedMessage(
  payloadValue: unknown,
): { role: 'user'; text: string; contents: DialogueContent[] } | null {
  const payload = asRecord(payloadValue)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  const displayContents = toDialogueContents(contents)
  if (displayContents.length === 0) {
    return null
  }
  return { role: 'user', text, contents: displayContents }
}
