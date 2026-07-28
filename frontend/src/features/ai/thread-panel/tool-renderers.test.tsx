import { afterEach, describe, expect, it } from 'vitest'
import {
  clearToolRenderers,
  getToolRenderer,
  registerToolRenderer,
  unregisterToolRenderer,
} from '@/features/ai/thread-panel'

describe('tool renderer registry', () => {
  afterEach(clearToolRenderers)

  it('trims names, ignores blank registrations, and supports unregistering', () => {
    const renderer = { renderCall: () => 'call', renderResult: () => 'result' }
    registerToolRenderer(' ', renderer)
    expect(getToolRenderer('')).toBeUndefined()
    registerToolRenderer(' bash ', renderer)
    expect(getToolRenderer('bash')).toBe(renderer)
    unregisterToolRenderer(' bash ')
    expect(getToolRenderer('bash')).toBeUndefined()
  })
})
