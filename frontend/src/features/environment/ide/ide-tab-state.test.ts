import { describe, expect, it } from 'vitest'
import {
  IDE_MAX_OPEN_ENVIRONMENTS,
  IDE_TAB_LAST_KEY,
  IDE_TAB_SESSION_KEY,
  createDefaultIdePresentation,
  initializeIdeTabState,
  saveIdePresentation,
  saveIdeSession,
  type IdePresentation,
  type IdeRecovery,
  type IdeTabStorageArea,
} from '@/features/environment/ide/ide-tab-state'

const ENV_A = 'a1000000-0000-4000-8000-000000000001'
const ENV_B = 'a1000000-0000-4000-8000-000000000002'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const VIEW_B = 'c2000000-0000-4000-8000-000000000002'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
const DAEMON_B = 'd2000000-0000-4000-8000-000000000002'

class MemoryStorage implements IdeTabStorageArea {
  readonly values = new Map<string, string>()
  writes = 0
  failWrites = false

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    if (this.failWrites) {
      throw new Error('storage unavailable')
    }
    this.writes += 1
    this.values.set(key, value)
  }
}

function presentation(partial: Partial<IdePresentation> = {}): IdePresentation {
  return {
    panelVisible: true,
    selectedEnvironmentId: ENV_A,
    openedEnvironmentIds: [ENV_A],
    ...partial,
  }
}

function recovery(partial: Partial<IdeRecovery> = {}): IdeRecovery {
  return {
    viewId: VIEW_A,
    daemonInstanceId: DAEMON_A,
    directory: '/workspace/a',
    ...partial,
  }
}

function sessionRaw(
  presentationValue: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
): string {
  return JSON.stringify({
    version: 1,
    presentation: presentationValue,
    recoveryByEnvironment,
  })
}

function localRaw(presentationValue: IdePresentation): string {
  return JSON.stringify({ version: 1, presentation: presentationValue })
}

describe('ide tab state', () => {
  it('restores a valid session snapshot and never overwrites localStorage during initialization', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const sessionValue = sessionRaw(presentation(), { [ENV_A]: recovery() })
    const localValue = localRaw(presentation({
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_B],
    }))
    session.values.set(IDE_TAB_SESSION_KEY, sessionValue)
    local.values.set(IDE_TAB_LAST_KEY, localValue)

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('session')
    expect(loaded.presentation).toEqual(presentation())
    expect(loaded.recoveryByEnvironment).toEqual({ [ENV_A]: recovery() })
    expect(loaded.sessionSaved).toBe(true)
    expect(session.values.get(IDE_TAB_SESSION_KEY)).toBe(sessionValue)
    expect(local.values.get(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(local.writes).toBe(0)
  })

  it('initializes from local presentation without inventing recovery identity', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const localValue = localRaw(presentation())
    local.values.set(IDE_TAB_LAST_KEY, localValue)

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('local')
    expect(loaded.presentation).toEqual(presentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
    expect(loaded.sessionSaved).toBe(true)
    expect(JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}')).toEqual({
      version: 1,
      presentation: presentation(),
      recoveryByEnvironment: {},
    })
    expect(local.values.get(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(local.writes).toBe(0)
  })

  it('falls back to local when the session snapshot is corrupt', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    session.values.set(IDE_TAB_SESSION_KEY, '{"version":1,"presentation":')
    local.values.set(IDE_TAB_LAST_KEY, localRaw(presentation()))

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('local')
    expect(loaded.presentation).toEqual(presentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
  })

  it('rejects local snapshots that smuggle runtime recovery or extra fields', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    local.values.set(
      IDE_TAB_LAST_KEY,
      JSON.stringify({
        version: 1,
        presentation: presentation(),
        recoveryByEnvironment: { [ENV_A]: recovery() },
      }),
    )

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('default')
    expect(loaded.presentation).toEqual(createDefaultIdePresentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
  })

  it.each([
    ['selected environment is not opened', presentation({
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_A],
    }), {}],
    ['opened environments repeat', presentation({
      openedEnvironmentIds: [ENV_A, ENV_A],
    }), { [ENV_A]: recovery() }],
    ['recovery references an unopened environment', presentation(), {
      [ENV_B]: recovery({ viewId: VIEW_B }),
    }],
    ['recovery has no viewId field', presentation(), {
      [ENV_A]: { daemonInstanceId: DAEMON_A, directory: '/workspace/a' },
    }],
    ['directory contains control characters', presentation(), {
      [ENV_A]: recovery({ directory: '/workspace/\u0000a' }),
    }],
    ['directory is empty', presentation(), {
      [ENV_A]: recovery({ directory: '' }),
    }],
    ['daemon instance is not canonical', presentation(), {
      [ENV_A]: recovery({ daemonInstanceId: 'daemon-a' }),
    }],
  ])('rejects a strict snapshot when %s', (_label, presentationValue, recoveryMap) => {
    const session = new MemoryStorage()
    session.values.set(
      IDE_TAB_SESSION_KEY,
      sessionRaw(presentationValue, recoveryMap as Record<string, IdeRecovery>),
    )

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: new MemoryStorage() })

    expect(loaded.source).toBe('default')
    expect(loaded.presentation).toEqual(createDefaultIdePresentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
  })

  it('writes session and local on a user event, keeping viewId only in session', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const next = presentation({
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_A, ENV_B],
    })
    const recoveries = {
      [ENV_A]: recovery(),
      [ENV_B]: recovery({ viewId: VIEW_B, daemonInstanceId: DAEMON_B, directory: '/workspace/b' }),
    }

    const saved = saveIdePresentation(next, recoveries, {
      sessionStorage: session,
      localStorage: local,
    })

    expect(saved).toEqual({ sessionSaved: true, localSaved: true })
    expect(JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}')).toMatchObject({
      presentation: next,
      recoveryByEnvironment: recoveries,
    })
    const localSnapshot = local.values.get(IDE_TAB_LAST_KEY) ?? ''
    expect(JSON.parse(localSnapshot)).toEqual({ version: 1, presentation: next })
    expect(localSnapshot).not.toContain(VIEW_A)
    expect(localSnapshot).not.toContain(DAEMON_A)
    expect(localSnapshot).not.toContain('/workspace')
  })

  it('claim success only appends session recovery and leaves local untouched', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const localValue = localRaw(presentation())
    local.values.set(IDE_TAB_LAST_KEY, localValue)

    const saved = saveIdeSession(
      presentation(),
      { [ENV_A]: recovery() },
      { sessionStorage: session, localStorage: local },
    )

    expect(saved).toBe(true)
    expect(JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}').recoveryByEnvironment)
      .toEqual({ [ENV_A]: recovery() })
    expect(local.values.get(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(local.writes).toBe(0)
  })

  it('refuses to persist a session snapshot above the 64 KiB scope limit', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const opened = Array.from({ length: IDE_MAX_OPEN_ENVIRONMENTS }, (_, index) => (
      `a1000000-0000-4000-8000-${String(index).padStart(12, '0')}`
    ))
    const recoveries = Object.fromEntries(opened.map((environmentId, index) => [
      environmentId,
      recovery({
        viewId: `c1000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
        daemonInstanceId: `d1000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
        directory: `/${'x'.repeat(4090)}`,
      }),
    ]))
    const next = presentation({
      selectedEnvironmentId: opened[0] ?? null,
      openedEnvironmentIds: opened,
    })

    const saved = saveIdePresentation(next, recoveries, {
      sessionStorage: session,
      localStorage: local,
    })

    expect(saved.sessionSaved).toBe(false)
    expect(saved.localSaved).toBe(true)
    expect(session.values.has(IDE_TAB_SESSION_KEY)).toBe(false)
  })

  it('keeps in-memory initialization when the session write is unavailable', () => {
    const session = new MemoryStorage()
    session.failWrites = true

    const loaded = initializeIdeTabState({
      sessionStorage: session,
      localStorage: new MemoryStorage(),
    })

    expect(loaded.sessionSaved).toBe(false)
    expect(loaded.source).toBe('default')
  })
})
