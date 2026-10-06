import { describe, expect, it, vi } from 'vitest'
import {
  TRANSCRIPT_READING_INTENT_EVENT,
  announceTranscriptReading,
  transcriptStreamRevision,
} from '@/features/ai/runtime/transcript-reading'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function assistant(id: string, text: string, status: DialogueMessage['status']): DialogueMessage {
  return { id, role: 'assistant', subjectEntryId: id, text, createdAt: '2026-07-28T10:00:00Z', status }
}

function tool(
  id: string,
  partialContents: DialogueMessage['partialContents'],
  status: DialogueMessage['status'],
): DialogueMessage {
  return {
    id,
    role: 'tool',
    subjectEntryId: id,
    toolCallId: `call-${id}`,
    toolName: 'bash',
    arguments: '{}',
    contents: [],
    partialContents,
    createdAt: '2026-07-28T10:00:00Z',
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

  it('ignores media and thinking-only updates (no revision, no auto-scroll)', () => {
    const resource: DialogueMessage['partialContents'] = [
      { type: 'resource', uri: 'blob://img', mediaType: 'image/png', name: 'shot.png' },
      { type: 'resource', uri: 'blob://clip', mediaType: 'video/mp4' },
    ]
    const withMedia = transcriptStreamRevision([tool('t1', resource, 'streaming')])
    const withMoreMedia = transcriptStreamRevision([
      tool('t1', [
        ...resource,
        { type: 'resource', uri: 'blob://big', mediaType: 'image/png', sizeBytes: 10_240 },
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
