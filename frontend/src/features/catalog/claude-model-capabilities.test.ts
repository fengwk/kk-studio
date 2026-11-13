import { describe, expect, it } from 'vitest'
import { supportsClaudeMidConvoSystemMessages } from '@/features/catalog/claude-model-capabilities'

describe('supportsClaudeMidConvoSystemMessages', () => {
  it.each([
    'claude-opus-4-8',
    'Claude-Opus-4.8',
    'anthropic/claude-opus-5',
    '~ANTHROPIC/Claude-Opus-5.5-20260723',
    'claude-sonnet-5-5-20260723',
    'claude-haiku-5.5',
    'claude-fable-5',
    'claude-mythos-5.1',
    'claude-fable-5-2-20260723',
  ])('supports %s', (modelId) => {
    expect(supportsClaudeMidConvoSystemMessages(modelId)).toBe(true)
  })

  it.each([
    'claude-sonnet-4-5',
    'claude-opus-4.7',
    'claude-haiku-5',
    'claude-fable-5.3',
    'claude-mythos-5.3',
    'claude-opus-5.5-2026072',
    'other/claude-opus-5.5',
  ])('does not support %s', (modelId) => {
    expect(supportsClaudeMidConvoSystemMessages(modelId)).toBe(false)
  })
})
