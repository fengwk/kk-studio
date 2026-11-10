import { describe, expect, it, vi } from 'vitest'
import {
  TRANSCRIPT_READING_INTENT_EVENT,
  announceTranscriptReading,
  transcriptStreamRevision,
} from '@/features/thread/runtime/transcript-reading'
import type {
  DialogueMessage,
  ToolContent,
  ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'

function assistant(id: string, text: string, status: DialogueMessage['status']): DialogueMessage {
  return { id, role: 'assistant', subjectEntryId: id, text, createdAt: 1785232800000, status }
}

function tool(
  id: string,
  partialContents: ToolDialogueMessage['partialContents'],
  status: DialogueMessage['status'],
  args = '{}',
): ToolDialogueMessage {
  return {
    id,
    role: 'tool',
    rendererKey: 'bash',
    phase: 'call',
    subjectEntryId: id,
    toolCallId: `call-${id}`,
    toolName: 'bash',
    arguments: args,
    contents: [],
    partialContents,
    createdAt: 1785232800000,
    status,
  }
}

describe('transcriptStreamRevision', () => {
  it('changes only when a streaming message text really updates', () => {
    const first = transcriptStreamRevision([assistant('a1', 'hello', 'streaming')])
    const longer = transcriptStreamRevision([assistant('a1', 'hello world', 'streaming')])
    const toolFirst = transcriptStreamRevision([
      assistant('a1', 'hello', 'done'),
      tool('t1', [{ type: 'text', text: 'partial' }], 'streaming'),
    ])
    const toolLonger = transcriptStreamRevision([
      assistant('a1', 'hello', 'done'),
      tool('t1', [{ type: 'text', text: 'partial output' }], 'streaming'),
    ])

    expect(first).not.toBeNull()
    expect(longer).not.toBeNull()
    expect(first).not.toBe(longer)
    expect(toolFirst).not.toBe(toolLonger)
  })

  it('changes while streaming write/edit arguments grow before any output exists', () => {
    // 意图：write/edit 的参数逐帧变长时卡片长高，必须产生新的 revision 以继续贴底。
    const short = transcriptStreamRevision([tool('t1', [], 'streaming', '{"path":"a.ts","content":"x"')])
    const longer = transcriptStreamRevision([
      tool('t1', [], 'streaming', '{"path":"a.ts","content":"xyz"'),
    ])
    expect(short).not.toBeNull()
    expect(short).not.toBe(longer)
    // 同样长度但换了调用身份（另一次 attempt）不能沿用旧签名。
    const otherCall = transcriptStreamRevision([
      { ...tool('t1', [], 'streaming', '{"path":"a.ts","content":"x"'), toolCallId: 'call-other' },
    ])
    expect(otherCall).not.toBe(short)
  })

  it('ignores media and thinking-only updates (no revision, no auto-scroll)', () => {
    const resource: ToolContent[] = [
      { type: 'resource', attachment: { type: 'image', name: 'shot.png', mime: 'image/png', data: 'blob://img' } },
      { type: 'resource', attachment: { type: 'video', name: 'clip.mp4', mime: 'video/mp4', data: 'blob://clip' } },
    ]
    const withMedia = transcriptStreamRevision([tool('t1', resource, 'streaming')])
    const withMoreMedia = transcriptStreamRevision([
      tool('t1', [
        ...resource,
        { type: 'resource', attachment: { type: 'image', name: 'big.png', mime: 'image/png', data: 'blob://big', size: 10_240 } },
      ], 'streaming'),
    ])
    // 只有媒体（异步加载/解码）变化时签名不变。
    expect(withMedia).toBe(withMoreMedia)

    // 只有思考、还没有正文时同样不产生新 revision。
    const thinkingOnly = transcriptStreamRevision([assistant('a1', '', 'streaming')])
    const thinkingOnlyLater = transcriptStreamRevision([assistant('a1', '', 'streaming')])
    expect(thinkingOnly).toBeNull()
    expect(thinkingOnlyLater).toBeNull()
  })

  it('serializes json fragments deterministically', () => {
    const value = { b: 1, a: [{ y: true, x: 'z' }] }
    const one = transcriptStreamRevision([tool('t1', [{ type: 'json', value }], 'streaming')])
    const two = transcriptStreamRevision([tool('t1', [{ type: 'json', value }], 'streaming')])
    expect(one).toBe(two)
    expect(one).not.toBe(transcriptStreamRevision([
      tool('t1', [{ type: 'json', value: { b: 1, a: [{ y: true, x: 'zz' }] } }], 'streaming'),
    ]))
  })

  it('is null when nothing is streaming (layout-only changes carry no signal)', () => {
    expect(transcriptStreamRevision([
      assistant('a1', 'hello', 'done'),
      tool('t1', [{ type: 'text', text: 'done' }], 'done'),
    ])).toBeNull()
    expect(transcriptStreamRevision([])).toBeNull()
  })
})

describe('announceTranscriptReading', () => {
  it('bubbles a pause-only reading intent carrying its source', () => {
    const host = document.createElement('div')
    const child = document.createElement('span')
    host.appendChild(child)
    const listener = vi.fn()
    host.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, listener)

    announceTranscriptReading(child, 'thinking')

    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener.mock.calls[0]?.[0]).toMatchObject({
      bubbles: true,
      detail: { source: 'thinking' },
    })
  })
})
