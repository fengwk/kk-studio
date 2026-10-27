import { describe, expect, it } from 'vitest'

import { RuntimeStatusTracker } from '../src/runtime-status.js'

describe('RuntimeStatusTracker', () => {
  it('advances revision only for changed content and ignores stale progress', () => {
    const tracker = new RuntimeStatusTracker('4.0.3')
    expect(tracker.snapshot()).toEqual({
      revision: '1',
      status: { daemonVersion: '4.0.3', update: null },
    })

    tracker.beginUpdate('4.0.4')
    expect(tracker.snapshot().revision).toBe('2')
    // 同目标重复开始不产生新快照。
    tracker.beginUpdate('4.0.4')
    expect(tracker.snapshot().revision).toBe('2')

    expect(tracker.reportProgress({ downloadedBytes: 10, totalBytes: 100 })).toBe(true)
    expect(tracker.snapshot()).toEqual({
      revision: '3',
      status: { daemonVersion: '4.0.3', update: { targetVersion: '4.0.4', downloadedBytes: 10, totalBytes: 100 } },
    })
    // 乱序旧回调与同形重复都不能回退或推进 revision。
    expect(tracker.reportProgress({ downloadedBytes: 5, totalBytes: 100 })).toBe(false)
    expect(tracker.reportProgress({ downloadedBytes: 10, totalBytes: 100 })).toBe(false)
    expect(tracker.snapshot().revision).toBe('3')

    expect(tracker.reportProgress({ downloadedBytes: 20, totalBytes: null })).toBe(true)
    expect(tracker.snapshot().revision).toBe('4')
    expect(tracker.clearUpdate()).toBe(true)
    expect(tracker.snapshot().revision).toBe('5')
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
    expect(tracker.snapshot().revision).toBe('2')
  })
})
