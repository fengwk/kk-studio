import { describe, expect, it } from 'vitest'
import { projectDurableEntry } from '@/features/chat/runtime/thread-timeline/entry-projection'
import type { DialogueMessage } from '@/features/chat/runtime/thread-timeline-types'
import { setLocale } from '@/shared/i18n'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

const settings = {
  agentName: 'coder',
  model: { providerName: 'openai', modelName: 'gone', variant: 'default' },
  environmentName: null,
}

function turnStart(fallbackModel: unknown): HarnessSessionEntryDTO {
  return {
    entryId: 'turn-1',
    sessionId: 'session-1',
    parentEntryId: 'root-1',
    entryType: 'TURN_START',
    payloadJson: JSON.stringify({ reason: 'INPUT', settings, fallbackModel }),
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

describe('TURN_START model fallback projection', () => {
  // 模型失效改用 Agent 默认模型时，时间线说明本轮实际使用的模型，且不当作设置变更。
  it('announces the default model that served the turn', () => {
    setLocale('en-US')
    const messages = project(
      turnStart({ providerName: 'openai', modelName: 'gpt-5', variant: 'default' }),
    )
    expect(messages).toHaveLength(1)
    expect(messages[0]).toMatchObject({ id: 'entry:turn-1:model-fallback', kind: 'settings_change' })
    expect(JSON.stringify(messages[0])).toContain('this turn used the agent default model')
  })

  it('stays silent when the turn ran on the thread model', () => {
    expect(project(turnStart(null))).toEqual([])
    expect(project(turnStart({ providerName: 'openai' }))).toEqual([])
  })
})
