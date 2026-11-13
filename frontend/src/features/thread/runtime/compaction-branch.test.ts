import { describe, expect, it } from 'vitest'
import { projectCompactionBranch } from '@/features/thread/runtime/compaction-branch'
import type { EntryType } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

const INSTRUCTION = '[Compaction mode]\nSummarize the conversation.'

function entry(
  entryId: string,
  entryType: EntryType,
  payload: unknown,
  usageCost: { currency: string; amount: string } | null = null,
): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-1',
    parentEntryId: null,
    entryType,
    payload,
    createdAt: 1767225600000,
    usageCost,
  }
}

function assistant(
  entryId: string,
  usage: Record<string, unknown>,
  usageCost: { currency: string; amount: string } | null = null,
): HarnessSessionEntryDTO {
  return entry(entryId, 'MESSAGE', {
    message: { role: 'ASSISTANT', contents: [{ type: 'text', text: 'summary draft' }] },
    assistantMetadata: { stopReason: 'COMPLETE', usage },
  }, usageCost)
}

describe('compaction branch projection', () => {
  it('loads the instruction, inherited amount, model calls and disabled tool results', () => {
    // 分支自己的 Entry：FORK 锚点 + 指令 + 工具循环 + 摘要结果；继承量取首次调用的输入估算。
    const branch = projectCompactionBranch([
      entry('fork', 'FORK', { mode: 'BRANCH', sourceEntryId: 'cut-1', sourceThreadId: null }),
      entry('instruction', 'CUSTOM_MESSAGE', {
        message: { role: 'USER', contents: [{ type: 'text', text: INSTRUCTION }] },
      }),
      entry('start', 'TURN_START', { reason: 'INPUT' }),
      assistant('first-call', {
        inputTokens: 100,
        outputTokens: 20,
        cacheReadTokens: 40,
        cacheWriteTokens: 10,
        reasoningTokens: 5,
        providerTotalTokens: 120,
      }),
      entry('tool-result', 'MESSAGE', {
        message: {
          role: 'TOOL',
          contents: [{
            type: 'tool_result',
            toolCallId: 'call-1',
            toolName: 'read',
            rendererKey: 'read',
            error: true,
            contents: [{ type: 'text', text: 'Tools are disabled in compaction mode.' }],
          }],
        },
      }),
      assistant('second-call', {
        inputTokens: 180,
        outputTokens: 30,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        reasoningTokens: 0,
        providerTotalTokens: 210,
      }, { currency: 'USD', amount: '0.0042' }),
      entry('end', 'TURN_END', { outcome: 'COMPLETED', continueModel: false }),
    ])

    expect(branch.instruction).toBe(INSTRUCTION)
    // 第一次模型调用的上下文输入 = 未缓存输入 + 缓存读写。
    expect(branch.inheritedContextTokens).toBe(150)
    expect(branch.modelCalls.map((call) => call.entryId)).toEqual(['first-call', 'second-call'])
    expect(branch.modelCalls[1]?.usage.cost).toEqual({ currency: 'USD', amount: '0.0042' })
    expect(branch.toolErrors).toEqual([
      { id: 'tool-result:call-1', toolName: 'read', text: 'Tools are disabled in compaction mode.' },
    ])
  })

  it('keeps inherited context unknown when the first call has no usable usage', () => {
    const branch = projectCompactionBranch([
      entry('instruction', 'CUSTOM_MESSAGE', {
        message: { role: 'USER', contents: [{ type: 'text', text: INSTRUCTION }] },
      }),
      assistant('first-call', { inputTokens: 'unknown' }),
    ])

    expect(branch.inheritedContextTokens).toBeNull()
    expect(branch.modelCalls).toHaveLength(1)
    expect(branch.modelCalls[0]?.usage.input).toBeNull()
  })

  it('ignores non-error tool results and tolerates an empty branch', () => {
    const branch = projectCompactionBranch([
      entry('tool-result', 'MESSAGE', {
        message: {
          role: 'TOOL',
          contents: [{
            type: 'tool_result',
            toolCallId: 'call-1',
            toolName: 'read',
            rendererKey: 'read',
            error: false,
            contents: [{ type: 'text', text: 'ok' }],
          }],
        },
      }),
    ])

    expect(branch).toEqual({
      instruction: null,
      inheritedContextTokens: null,
      modelCalls: [],
      toolErrors: [],
    })
  })
})
