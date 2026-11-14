import { asRecord, getRecordList } from '@/features/thread/runtime/thread-timeline/payload-json'
import { contentText } from '@/features/thread/runtime/thread-timeline/content-utils'

export function extractQueuedMessage(
  payloadValue: unknown,
): { role: 'user'; text: string } | null {
  const payload = asRecord(payloadValue)
  const message = asRecord(payload.message)
  const contents = getRecordList(message.contents)
  const text = contents.map(contentText).filter(Boolean).join('\n')
  if (!text) {
    return null
  }
  return { role: 'user', text }
}
