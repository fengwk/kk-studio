import { describe, expect, it } from 'vitest'
import {
  decodeThreadUiPreferences,
  DEFAULT_THREAD_UI_PREFERENCES,
} from '@/features/ai/runtime/thread-ui-preferences'

describe('decodeThreadUiPreferences', () => {
  it('uses the explicit browser-local toggle values', () => {
    expect(
      decodeThreadUiPreferences(
        '{"taskStatusEnabled":false,"notificationsEnabled":true}',
      ),
    ).toEqual({
      taskStatusEnabled: false,
      notificationsEnabled: true,
    })
  })

  it('falls back field by field for invalid or missing local data', () => {
    expect(decodeThreadUiPreferences('{"taskStatusEnabled":"no"}')).toEqual(
      DEFAULT_THREAD_UI_PREFERENCES,
    )
    expect(decodeThreadUiPreferences('not-json')).toEqual(
      DEFAULT_THREAD_UI_PREFERENCES,
    )
  })
})
