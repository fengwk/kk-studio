import { describe, expect, it } from 'vitest'
import { IDE_SESSION_STORAGE_KEY, initializeIdeSession, saveIdeSession, type IdeRecovery } from '@/features/environment/ide/ide-session-state'
import type { TabStorage } from '@/shared/lib/tab-storage'

class MemoryStorage implements TabStorage {
  sessionStorage = this
  localStorage = this
  values = new Map<string, string>()
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) { this.values.set(key, value) }
}
const id = '123e4567-e89b-42d3-a456-426614174000'
describe('IDE session recovery', () => {
  it('persists only session identity and recovery; rejects malformed pending claims', () => {
    const storage = new MemoryStorage()
    const recovery: IdeRecovery = { environmentId: id, viewId: id, daemonInstanceId: id, bindingEpoch: '1', pending: {
      mode: 'resume', viewId: id, claimId: id, expectedBindingEpoch: '1', expectedDaemonInstanceId: id,
    } }
    expect(saveIdeSession(id, { [id]: recovery }, storage)).toBe(true)
    const raw = JSON.parse(storage.getItem(IDE_SESSION_STORAGE_KEY)!)
    expect(Object.keys(raw).sort()).toEqual(['browserSessionId', 'recoveryByView'])
    expect(initializeIdeSession(storage)).toMatchObject({ browserSessionId: id, recoveryByView: { [id]: recovery }, recoveryError: false })
    raw.recoveryByView[id].pending.expectedBindingEpoch = '01'
    storage.setItem(IDE_SESSION_STORAGE_KEY, JSON.stringify(raw))
    expect(initializeIdeSession(storage)).toMatchObject({ recoveryByView: {}, recoveryError: true, sessionSaved: false })
    expect(JSON.parse(storage.getItem(IDE_SESSION_STORAGE_KEY)!)).toEqual(raw)
  })
})
