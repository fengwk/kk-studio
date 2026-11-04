import { describe, expect, it } from 'vitest'
import { projectDurableEntry } from '@/features/thread/runtime/thread-timeline/entry-projection'
import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
import { setLocale } from '@/shared/i18n'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

const settings = {
  agentName: 'coder',
  execution: createBuiltinExecution('gone', 'default'),
  environmentName: null,
}

function turnStart(executedSelection: unknown): HarnessSessionEntryDTO {
  return {
    entryId: 'turn-1',
    sessionId: 'session-1',
    parentEntryId: 'root-1',
    entryType: 'TURN_START',
    payloadJson: JSON.stringify({ reason: 'INPUT', settings, executedSelection }),
    createTime: 1792368000,
    usageCost: null,
  }
}

function project(entry: HarnessSessionEntryDTO): DialogueMessage[] {
  const messages: DialogueMessage[] = []
  projectDurableEntry(entry, messages, new Map(), {
    pendingTurnSummary: null,
    lastSettings: settings,
  })
  return messages
}

describe('TURN_START executedSelection fallback projection', () => {
  // 模型失效改用 Agent 默认模型时，时间线说明本轮实际使用的模型，且不当作设置变更。
  it('announces the default model that served the turn', () => {
    setLocale('en-US')
    const messages = project(
      turnStart(createBuiltinExecution('gpt-5', 'default')),
    )
    expect(messages).toHaveLength(1)
    expect(messages[0]).toMatchObject({ id: 'entry:turn-1:model-fallback', kind: 'settings_change' })
    expect(JSON.stringify(messages[0])).toContain('this turn used the agent default model')
  })

  it('stays silent when the turn ran on the thread model', () => {
    expect(project(turnStart(null))).toEqual([])
    expect(project(turnStart({ driver: 'builtin', settings: {} }))).toEqual([])
  })
})
