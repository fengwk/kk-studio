import { describe, expect, it } from 'vitest'
import { hasThreadNameConflict } from './thread-name-conflict'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import type { RuntimeThreadSummaryDTO } from '@/shared/api/contracts/ai-runtime'

const summary: RuntimeThreadSummaryDTO = {
  threadId: 'root', parentThreadId: null, name: 'main', createdAt: null, updatedAt: null,
  status: 'IDLE', processing: false,
  model: { providerName: 'p', modelName: 'm', variant: 'v' }, headMessagePreview: null,
}
const draft: PaneTarget = { kind: 'NEW_THREAD_DRAFT', sessionId: 's', startEntryId: 'root', threadName: 'main' }

describe('Thread name namespace', () => {
  it('normalizes roots but excludes bound subagents and case differences', () => {
    expect(hasThreadNameConflict(' main\u0085', 's', 'p1', [summary], {})).toBe(true)
    expect(hasThreadNameConflict('main', 's', 'p1', [{ ...summary, parentThreadId: 'parent' }], {})).toBe(false)
    expect(hasThreadNameConflict('Main', 's', 'p1', [summary], {})).toBe(false)
  })

  it('includes hidden/pending local drafts in this Session, except the destination itself', () => {
    expect(hasThreadNameConflict('main', 's', 'p1', [], { hidden: draft })).toBe(true)
    expect(hasThreadNameConflict('main', 's', 'p1', [], { p1: draft })).toBe(false)
    expect(hasThreadNameConflict('main', 'other', 'p1', [], { hidden: draft })).toBe(false)
    expect(hasThreadNameConflict('main', 's', 'p1', [], { bound: { kind: 'BOUND_THREAD', threadId: 'root' }, blank: { kind: 'NEW_SESSION_DRAFT' } })).toBe(false)
  })
})
