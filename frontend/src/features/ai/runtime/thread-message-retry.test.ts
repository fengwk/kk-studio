import { describe, expect, it } from 'vitest'
import {
  sameThreadMessagePayload,
  type ThreadMessagePayload,
} from '@/features/ai/runtime/thread-message-retry'

const payload: ThreadMessagePayload = {
  kind: 'USER_MESSAGE',
  role: 'user',
  content: 'retry me',
  agentName: 'assistant',
  yoloEnabled: false,
  firstSendContext: null,
}

describe('sameThreadMessagePayload', () => {
  it('keeps actual message setting changes in retry identity', () => {
    expect(
      sameThreadMessagePayload(payload, {
        ...payload,
        yoloEnabled: true,
      }),
    ).toBe(false)
  })
})
