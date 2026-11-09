import { describe, expect, it } from 'vitest'
import {
  IDE_MAX_OPEN_ENVIRONMENTS,
  IDE_MAX_RECENT_DIRECTORIES,
  IDE_RECENT_DIRECTORIES_KEY,
  IDE_TAB_LAST_KEY,
  IDE_TAB_SESSION_KEY,
  createDefaultIdePresentation,
  initializeIdeTabState,
  readRecentDirectories,
  recordRecentDirectory,
  saveIdePresentation,
  saveIdeSession,
  type IdePendingClaim,
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
const CLAIM_A = 'e1000000-0000-4000-8000-000000000001'
const BROWSER_A = 'b1000000-0000-4000-8000-000000000001'

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

function pending(partial: Partial<IdePendingClaim> = {}): IdePendingClaim {
  return {
    mode: 'resume',
    viewId: VIEW_A,
    claimId: CLAIM_A,
    expectedBindingEpoch: '10',
    expectedDaemonInstanceId: DAEMON_A,
    directory: '/workspace/a',
    ...partial,
  }
}

function recovery(partial: Partial<IdeRecovery> = {}): IdeRecovery {
  return {
    viewId: VIEW_A,
    daemonInstanceId: DAEMON_A,
    bindingEpoch: '10',
    directory: '/workspace/a',
    pending: undefined,
    ...partial,
  }
}

function recoveryJson(overrides: Partial<IdeRecovery> = {}): Record<string, unknown> {
  const value = recovery(overrides)
  return {
    viewId: value.viewId,
    daemonInstanceId: value.daemonInstanceId,
    bindingEpoch: value.bindingEpoch ?? null,
    directory: value.directory ?? null,
    pending: value.pending == null ? null : {
      mode: value.pending.mode,
      viewId: value.pending.viewId,
      claimId: value.pending.claimId,
      expectedBindingEpoch: value.pending.expectedBindingEpoch,
      expectedDaemonInstanceId: value.pending.expectedDaemonInstanceId,
      directory: value.pending.directory ?? null,
    },
  }
}

function sessionRaw(
  browserSessionId: string,
  presentationValue: IdePresentation,
  recoveryByEnvironment: Record<string, unknown>,
): string {
  return JSON.stringify({
    browserSessionId,
    presentation: presentationValue,
    recoveryByEnvironment,
  })
}

function localRaw(presentationValue: IdePresentation): string {
  return JSON.stringify({ presentation: presentationValue })
}

describe('ide tab state', () => {
  it('restores a valid session snapshot with stable browserSessionId and never writes local on load', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const sessionValue = sessionRaw(BROWSER_A, presentation(), {
      [ENV_A]: recoveryJson(),
    })
    const localValue = localRaw(presentation({
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_B],
    }))
    session.values.set(IDE_TAB_SESSION_KEY, sessionValue)
    local.values.set(IDE_TAB_LAST_KEY, localValue)

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('session')
    expect(loaded.browserSessionId).toBe(BROWSER_A)
    expect(loaded.presentation).toEqual(presentation())
    expect(loaded.recoveryByEnvironment[ENV_A]).toMatchObject({
      viewId: VIEW_A,
      daemonInstanceId: DAEMON_A,
      bindingEpoch: '10',
      directory: '/workspace/a',
    })
    expect(loaded.sessionSaved).toBe(true)
    expect(session.values.get(IDE_TAB_SESSION_KEY)).toBe(sessionValue)
    expect(local.values.get(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(local.writes).toBe(0)
  })

  it('initializes from local presentation with a fresh browserSessionId and no runtime identity', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const localValue = localRaw(presentation())
    local.values.set(IDE_TAB_LAST_KEY, localValue)

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: local })

    expect(loaded.source).toBe('local')
    expect(loaded.browserSessionId).toMatch(/^[0-9a-f-]{36}$/)
    expect(loaded.browserSessionId).not.toBe(BROWSER_A)
    expect(loaded.presentation).toEqual(presentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
    expect(loaded.sessionSaved).toBe(true)
    expect(JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}')).toMatchObject({
      browserSessionId: loaded.browserSessionId,
      presentation: presentation(),
      recoveryByEnvironment: {},
    })
    expect(local.values.get(IDE_TAB_LAST_KEY)).toBe(localValue)
    expect(local.writes).toBe(0)
  })

  it('falls back to local when the session snapshot is corrupt', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    session.values.set(IDE_TAB_SESSION_KEY, '{"presentation":')
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
        presentation: presentation(),
        recoveryByEnvironment: { [ENV_A]: recoveryJson() },
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
    }), { [ENV_A]: recoveryJson() }],
    ['opened environments repeat', presentation({
      openedEnvironmentIds: [ENV_A, ENV_A],
    }), { [ENV_A]: recoveryJson() }],
    ['recovery references an unopened environment', presentation(), {
      [ENV_B]: recoveryJson({ viewId: VIEW_B }),
    }],
    ['recovery has no viewId field', presentation(), {
      [ENV_A]: { ...recoveryJson(), viewId: undefined },
    }],
    ['directory contains control characters', presentation(), {
      [ENV_A]: recoveryJson({ directory: '/workspace/\u0000a' }),
    }],
    ['directory is empty', presentation(), {
      [ENV_A]: recoveryJson({ directory: '' }),
    }],
    ['daemon instance is not canonical', presentation(), {
      [ENV_A]: recoveryJson({ daemonInstanceId: 'daemon-a' }),
    }],
    ['bindingEpoch is not canonical', presentation(), {
      [ENV_A]: recoveryJson({ bindingEpoch: '01' }),
    }],
    ['pending create freezes a non-zero epoch', presentation(), {
      [ENV_A]: recoveryJson({
        bindingEpoch: undefined,
        pending: pending({ mode: 'create', expectedBindingEpoch: '1' }),
      }),
    }],
    ['pending resume freezes epoch zero', presentation(), {
      [ENV_A]: recoveryJson({ pending: pending({ expectedBindingEpoch: '0' }) }),
    }],
    ['pending viewId disagrees with recovery', presentation(), {
      [ENV_A]: recoveryJson({ pending: pending({ viewId: VIEW_B }) }),
    }],
    ['pending claimId is not canonical', presentation(), {
      [ENV_A]: recoveryJson({ pending: pending({ claimId: 'claim-a' }) }),
    }],
  ])('rejects a strict snapshot when %s', (_label, presentationValue, recoveryMap) => {
    const session = new MemoryStorage()
    session.values.set(
      IDE_TAB_SESSION_KEY,
      sessionRaw(BROWSER_A, presentationValue, recoveryMap),
    )

    const loaded = initializeIdeTabState({ sessionStorage: session, localStorage: new MemoryStorage() })

    expect(loaded.source).toBe('default')
    expect(loaded.browserSessionId).not.toBe(BROWSER_A)
    expect(loaded.presentation).toEqual(createDefaultIdePresentation())
    expect(loaded.recoveryByEnvironment).toEqual({})
  })

  it('writes session and local on a user event, keeping runtime identity only in session', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const next = presentation({
      selectedEnvironmentId: ENV_B,
      openedEnvironmentIds: [ENV_A, ENV_B],
    })
    const recoveries = {
      [ENV_A]: recovery(),
      [ENV_B]: recovery({
        viewId: VIEW_B,
        daemonInstanceId: DAEMON_B,
        directory: '/workspace/b',
      }),
    }

    const saved = saveIdePresentation(BROWSER_A, next, recoveries, {
      sessionStorage: session,
      localStorage: local,
    })

    expect(saved).toEqual({ sessionSaved: true, localSaved: true })
    expect(JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}')).toMatchObject({
      browserSessionId: BROWSER_A,
      presentation: next,
      recoveryByEnvironment: {
        [ENV_A]: expect.objectContaining({ viewId: VIEW_A }),
        [ENV_B]: expect.objectContaining({ viewId: VIEW_B }),
      },
    })
    const localSnapshot = local.values.get(IDE_TAB_LAST_KEY) ?? ''
    expect(JSON.parse(localSnapshot)).toEqual({ presentation: next })
    expect(localSnapshot).not.toContain(VIEW_A)
    expect(localSnapshot).not.toContain(DAEMON_A)
    expect(localSnapshot).not.toContain('/workspace')
  })

  it('persists a frozen pending claim before the command is sent', () => {
    const session = new MemoryStorage()
    const local = new MemoryStorage()
    const localValue = localRaw(presentation())
    local.values.set(IDE_TAB_LAST_KEY, localValue)
    const pendingClaim = pending({ directory: null })

    const saved = saveIdeSession(
      BROWSER_A,
      presentation(),
      { [ENV_A]: recovery({ pending: pendingClaim }) },
      { sessionStorage: session, localStorage: local },
    )

    expect(saved).toBe(true)
    const snapshot = JSON.parse(session.values.get(IDE_TAB_SESSION_KEY) ?? '{}') as {
      browserSessionId: string
      recoveryByEnvironment: Record<string, { pending: Record<string, unknown> }>
    }
    expect(snapshot.browserSessionId).toBe(BROWSER_A)
    expect(snapshot.recoveryByEnvironment[ENV_A]?.pending).toEqual({
      mode: 'resume',
      viewId: VIEW_A,
      claimId: CLAIM_A,
      expectedBindingEpoch: '10',
      expectedDaemonInstanceId: DAEMON_A,
      directory: null,
    })
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

    const saved = saveIdePresentation(BROWSER_A, next, recoveries, {
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

  describe('recent directories', () => {
    it('records per environment with newest first and no duplicates', () => {
      const local = new MemoryStorage()
      const storage = { sessionStorage: new MemoryStorage(), localStorage: local }

      expect(recordRecentDirectory(ENV_A, '/workspace/a', storage)).toEqual(['/workspace/a'])
      expect(recordRecentDirectory(ENV_A, '/workspace/b', storage)).toEqual([
        '/workspace/b',
        '/workspace/a',
      ])
      expect(recordRecentDirectory(ENV_A, '/workspace/a', storage)).toEqual([
        '/workspace/a',
        '/workspace/b',
      ])
      expect(recordRecentDirectory(ENV_B, '/workspace/c', storage)).toEqual(['/workspace/c'])
      expect(readRecentDirectories(ENV_A, storage)).toEqual(['/workspace/a', '/workspace/b'])
      expect(readRecentDirectories(ENV_B, storage)).toEqual(['/workspace/c'])
      expect(readRecentDirectories(ENV_A)).toEqual([])
    })

    it('bounds the list and ignores invalid stored entries', () => {
      const local = new MemoryStorage()
      const storage = { localStorage: local }
      for (let index = 0; index < IDE_MAX_RECENT_DIRECTORIES + 3; index += 1) {
        recordRecentDirectory(ENV_A, `/workspace/${index}`, storage)
      }
      expect(readRecentDirectories(ENV_A, storage)).toHaveLength(IDE_MAX_RECENT_DIRECTORIES)
      expect(readRecentDirectories(ENV_A, storage)[0]).toBe(
        `/workspace/${IDE_MAX_RECENT_DIRECTORIES + 2}`,
      )

      local.values.set(IDE_RECENT_DIRECTORIES_KEY, JSON.stringify({
        byEnvironment: {
          [ENV_A]: ['/valid', 7, '', '/valid', '/bad\u0000path'],
          'not-a-uuid': ['/ignored'],
        },
      }))
      expect(readRecentDirectories(ENV_A, storage)).toEqual(['/valid'])

      local.values.set(IDE_RECENT_DIRECTORIES_KEY, '{"unexpected":true}')
      expect(readRecentDirectories(ENV_A, storage)).toEqual([])
    })
  })
})
