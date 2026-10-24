import { describe, expect, it } from 'vitest'
import {
  extractQueuedCustomMessages,
  type QueuedCustomMessage,
} from './queued-custom-messages'
import type { HarnessThreadCommandDTO } from '@/shared/api/generated/contracts'

function createCommand(overrides: Partial<HarnessThreadCommandDTO>): HarnessThreadCommandDTO {
  return {
    threadId: 'child-thread-1',
    sequence: '1',
    type: 'CUSTOM_MESSAGE',
    state: 'QUEUED',
    idempotencyKey: 'cmd-1',
    payloadJson: JSON.stringify({
      message: {
        role: 'USER',
        contents: [{ type: 'text', text: '补充要求内容' }],
      },
    }),
    cancelledAt: null,
    createTime: 1774958400,
    ...overrides,
  }
}

describe('extractQueuedCustomMessages', () => {
  it('从 QUEUED CUSTOM_MESSAGE 中提取稳定 id、sequence 与实际文本', () => {
    const commands: HarnessThreadCommandDTO[] = [
      createCommand({
        sequence: '10',
        idempotencyKey: 'key-10',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: '请优先补充单测' }],
          },
        }),
      }),
      createCommand({
        sequence: '11',
        idempotencyKey: 'key-11',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [
              { type: 'text', text: '第一行要求' },
              { type: 'text', text: '第二行要求' },
            ],
          },
        }),
      }),
    ]

    const result = extractQueuedCustomMessages(commands)

    expect(result).toEqual<QueuedCustomMessage[]>([
      {
        id: 'key-10',
        sequence: '10',
        text: '请优先补充单测',
      },
      {
        id: 'key-11',
        sequence: '11',
        text: '第一行要求\n第二行要求',
      },
    ])
  })

  it('过滤非 QUEUED 状态的 CUSTOM_MESSAGE（如 APPLIED 或已取消），应用后自然移除', () => {
    const commands: HarnessThreadCommandDTO[] = [
      createCommand({ sequence: '1', state: 'APPLIED' }),
      createCommand({ sequence: '2', state: 'CANCELLED' }),
      createCommand({
        sequence: '3',
        state: 'QUEUED',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: '仍在队列中' }],
          },
        }),
      }),
    ]

    const result = extractQueuedCustomMessages(commands)

    expect(result).toHaveLength(1)
    expect(result[0]?.sequence).toBe('3')
    expect(result[0]?.text).toBe('仍在队列中')
  })

  it('不将 USER_MESSAGE、GOAL、NOTIFICATION 或设置命令当作待处理补充要求', () => {
    const commands: HarnessThreadCommandDTO[] = [
      createCommand({ sequence: '1', type: 'USER_MESSAGE' }),
      createCommand({ sequence: '2', type: 'GOAL' }),
      createCommand({ sequence: '3', type: 'NOTIFICATION' }),
      createCommand({ sequence: '4', type: 'SET_AGENT' }),
      createCommand({ sequence: '5', type: 'CUSTOM_MESSAGE' }),
    ]

    const result = extractQueuedCustomMessages(commands)

    expect(result).toHaveLength(1)
    expect(result[0]?.sequence).toBe('5')
  })

  it('忽略空文本或空 contents，不生成空项', () => {
    const commands: HarnessThreadCommandDTO[] = [
      createCommand({
        sequence: '1',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: '   ' }],
          },
        }),
      }),
      createCommand({
        sequence: '2',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [],
          },
        }),
      }),
      createCommand({
        sequence: '3',
        payloadJson: JSON.stringify({}),
      }),
      createCommand({
        sequence: '4',
        payloadJson: 'invalid json',
      }),
    ]

    const result = extractQueuedCustomMessages(commands)

    expect(result).toEqual([])
  })

  it('仅展示规范 text 内容，不把缺失或其他 type 的内容猜成文本', () => {
    const commands: HarnessThreadCommandDTO[] = [
      createCommand({
        sequence: '7',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [
              { text: '缺少类型' },
              { type: 'image', text: '非文本内容' },
              { type: 'text', text: '规范文本' },
            ],
          },
        }),
      }),
    ]

    const result = extractQueuedCustomMessages(commands)

    expect(result).toEqual([
      {
        id: 'cmd-1',
        sequence: '7',
        text: '规范文本',
      },
    ])
  })
})
