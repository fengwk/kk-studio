import { describe, expect, it } from 'vitest'

import { RuntimeStatusTracker } from '../src/runtime-status.js'

describe('RuntimeStatusTracker', () => {
  it('advances revision only for changed content and ignores stale progress', () => {
    const tracker = new RuntimeStatusTracker('4.0.3')
    const initial = tracker.snapshot()
    expect(initial.revision).toBe(1n)
    expect(initial.status?.daemonVersion).toBe('4.0.3')
    expect(initial.status?.update).toBeUndefined()

    tracker.beginUpdate('4.0.4')
    expect(tracker.snapshot().revision).toBe(2n)
    // 同目标重复开始不产生新快照。
    tracker.beginUpdate('4.0.4')
    expect(tracker.snapshot().revision).toBe(2n)

    expect(tracker.reportProgress({ downloadedBytes: 10, totalBytes: 100 })).toBe(true)
    const withProgress = tracker.snapshot()
    expect(withProgress.revision).toBe(3n)
    expect(withProgress.status?.update?.targetVersion).toBe('4.0.4')
    expect(withProgress.status?.update?.downloadedBytes).toBe(10n)
    expect(withProgress.status?.update?.totalBytes).toBe(100n)

    // 乱序旧回调与同形重复都不能回退或推进 revision。
    expect(tracker.reportProgress({ downloadedBytes: 5, totalBytes: 100 })).toBe(false)
    expect(tracker.reportProgress({ downloadedBytes: 10, totalBytes: 100 })).toBe(false)
    expect(tracker.snapshot().revision).toBe(3n)

    expect(tracker.reportProgress({ downloadedBytes: 20, totalBytes: null })).toBe(true)
    expect(tracker.snapshot().revision).toBe(4n)
    expect(tracker.clearUpdate()).toBe(true)
    expect(tracker.snapshot().revision).toBe(5n)
    expect(tracker.clearUpdate()).toBe(false)
    expect(tracker.getUpdate()).toBeNull()
  })

  it('rejects invalid progress without corrupting the snapshot', () => {
    const tracker = new RuntimeStatusTracker('4.0.3')
    expect(() => tracker.reportProgress({ downloadedBytes: 1, totalBytes: null })).toThrow(/active update/)
    tracker.beginUpdate('4.0.4')
    expect(() => tracker.reportProgress({ downloadedBytes: -1, totalBytes: null })).toThrow(/downloadedBytes/)
    expect(() => tracker.reportProgress({ downloadedBytes: 2, totalBytes: 1 })).toThrow(/totalBytes/)
    expect(() => tracker.reportProgress({ downloadedBytes: 1.5, totalBytes: null })).toThrow(/downloadedBytes/)
    expect(tracker.getUpdate()).toEqual({ targetVersion: '4.0.4', downloadedBytes: 0, totalBytes: null })
    expect(tracker.snapshot().revision).toBe(2n)
  })

  it('throws RangeError when revision exceeds LONG_MAX', () => {
    const tracker = new RuntimeStatusTracker('4.0.3')
    ;(tracker as any).revision = 9223372036854775807n
    expect(() => tracker.beginUpdate('4.0.4')).toThrow(RangeError)
  })
})
