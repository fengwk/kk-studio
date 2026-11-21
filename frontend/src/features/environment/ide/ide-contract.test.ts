import { describe, expect, it } from 'vitest'
import type {
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'
import {
  buildOpenRequest,
  canonicalJson,
  IdeProtocolError,
  type IdeWireSnapshotView,
  type IdeWireView,
  normalizeIdeView,
  validateCommandView,
  validateOpenResponse,
  validateViewSnapshot,
  viewFingerprint,
} from '@/features/environment/ide/ide-contract'
import type { IdePendingClaim } from '@/features/environment/ide/ide-tab-state'

const ENV = 'a1000000-0000-4000-8000-000000000001'
const VIEW = 'c1000000-0000-4000-8000-000000000001'
const DAEMON = 'd1000000-0000-4000-8000-000000000001'
const CLAIM = 'e1000000-0000-4000-8000-000000000001'
const EVENT_NODE = 'f1000000-0000-4000-8000-000000000001'
const CONNECTION = 'f2000000-0000-4000-8000-000000000002'
const BROWSER = 'b1000000-0000-4000-8000-000000000001'

function view(overrides: Partial<EnvironmentIdeViewDTO> = {}): EnvironmentIdeViewDTO {
  return {
    viewId: VIEW,
    environmentId: ENV,
    browserSessionId: BROWSER,
    eventNodeId: EVENT_NODE,
    connectionId: CONNECTION,
    claimId: CLAIM,
    daemonInstanceId: DAEMON,
    bindingEpoch: '10',
    directory: '/workspace/a|b',
    workspacePath: '/workspace/a|b',
    state: 'OPEN',
    ...overrides,
  }
}

/** 仅用于构造服务端畸形回执；正常 fixture 全部使用精确 DTO 类型。 */
function malformedView(patch: Record<string, unknown>): EnvironmentIdeViewDTO {
  return { ...view(), ...patch } as EnvironmentIdeViewDTO
}

/** 无目录 workspace 的 wire 视图：服务端省略 directory。 */
function viewWithoutDirectory(overrides: Partial<IdeWireView> = {}): IdeWireView {
  const wire: IdeWireView = { ...view() }
  delete wire.directory
  return { ...wire, ...overrides }
}

function snapshot(overrides: Partial<IdeWireSnapshotView> = {}): IdeWireSnapshotView {
  return {
    daemonInstanceId: DAEMON,
    revision: '20',
    view: view(),
    ...overrides,
  }
}

function pending(overrides: Partial<IdePendingClaim> = {}): IdePendingClaim {
  return {
    mode: 'resume',
    viewId: VIEW,
    claimId: CLAIM,
    expectedBindingEpoch: '10',
    expectedDaemonInstanceId: DAEMON,
    directory: '/workspace/a|b',
    ...overrides,
  }
}

describe('IDE contract validation', () => {
  it('canonical JSON is key-order independent and does not collide on path separators', () => {
    expect(canonicalJson({ b: 1, a: { d: 'x|y', c: ['p|q'] } }))
      .toBe(canonicalJson({ a: { c: ['p|q'], d: 'x|y' }, b: 1 }))
    expect(canonicalJson({ path: 'a|b' })).not.toBe(canonicalJson({ path: 'a', extra: 'b' }))
    expect(viewFingerprint(view())).toBe(viewFingerprint(view()))
    expect(viewFingerprint(view())).not.toBe(viewFingerprint(malformedView({
      directory: '/workspace/a',
      workspacePath: 'b',
    })))
  })

  it('accepts a canonical single-view snapshot with revision gaps and bindingEpoch <= revision', () => {
    const accepted = validateViewSnapshot(ENV, VIEW, BROWSER, snapshot())
    expect(accepted.revision).toBe('20')
    expect(accepted.daemonInstanceId).toBe(DAEMON)
    expect(accepted.view?.bindingEpoch).toBe('10')
  })

  it('normalizes a wire null directory and an omitted directory to the same no-directory view', () => {
    expect(normalizeIdeView({ ...view(), directory: null })).toEqual(viewWithoutDirectory())
    expect(viewFingerprint({ ...view(), directory: null }))
      .toBe(viewFingerprint(viewWithoutDirectory()))
    expect(viewFingerprint(viewWithoutDirectory())).not.toBe(viewFingerprint(view()))
  })

  /** 零根 workspace 没有目录；快照指纹不能因 wire 的 null/缺省写法而冲突。 */
  it('accepts a zero-root snapshot with a null or omitted directory', () => {
    const nullDirectory = validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: { ...view(), directory: null },
    }))
    const omittedDirectory = validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: viewWithoutDirectory(),
    }))
    expect(nullDirectory.view?.directory).toBeUndefined()
    expect(omittedDirectory.view?.directory).toBeUndefined()
    expect(omittedDirectory.view?.workspacePath).toBe('/workspace/a|b')
    expect(viewFingerprint(nullDirectory.view)).toBe(viewFingerprint(omittedDirectory.view))
  })

  it('rejects a present directory that is empty or contains control characters', () => {
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: malformedView({ directory: '' }),
    }))).toThrow(IdeProtocolError)
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: malformedView({ directory: '/workspace/\u0000a' }),
    }))).toThrow(IdeProtocolError)
    expect(() => validateCommandView(ENV, BROWSER, VIEW, malformedView({
      directory: '/workspace/\u0000a',
    }))).toThrow(IdeProtocolError)
  })

  it('rejects a snapshot whose binding epoch exceeds the revision or omits workspacePath', () => {
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      revision: '9',
    }))).toThrow(IdeProtocolError)
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: malformedView({ workspacePath: undefined }),
    }))).toThrow(IdeProtocolError)
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: malformedView({ workspacePath: '' }),
    }))).toThrow(IdeProtocolError)
  })

  it('rejects entryUrl in a pure read snapshot and mismatched daemon instance', () => {
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: malformedView({ entryUrl: '/ide/x' }),
    }))).toThrow(IdeProtocolError)
    expect(() => validateViewSnapshot(ENV, VIEW, BROWSER, snapshot({
      view: view({ daemonInstanceId: 'd2000000-0000-4000-8000-000000000002' }),
    }))).toThrow(IdeProtocolError)
  })

  it('requires a complete workspacePath in command receipts instead of falling back to directory', () => {
    expect(() => validateCommandView(ENV, BROWSER, VIEW, malformedView({
      workspacePath: undefined,
    }))).toThrow(IdeProtocolError)
    expect(() => validateCommandView(ENV, BROWSER, VIEW, malformedView({
      workspacePath: '',
    }))).toThrow(IdeProtocolError)
    expect(validateCommandView(ENV, BROWSER, VIEW, view())).toMatchObject({
      bindingEpoch: '10',
      daemonInstanceId: DAEMON,
      workspacePath: '/workspace/a|b',
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
    })
  })

  it('validates the frozen claim identity, instance and epoch on OPEN receipts', () => {
    const request = buildOpenRequest(pending(), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      heartbeatIntervalMillis: '20000',
    })
    const response = view({
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    })
    expect(validateOpenResponse(ENV, request, response)).toMatchObject({
      viewId: VIEW,
      daemonInstanceId: DAEMON,
      bindingEpoch: '10',
    })
    expect(() => validateOpenResponse(ENV, request, view({
      claimId: 'e2000000-0000-4000-8000-000000000002',
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    }))).toThrow(IdeProtocolError)
    expect(() => validateOpenResponse(ENV, request, view({
      daemonInstanceId: 'd2000000-0000-4000-8000-000000000002',
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    }))).toThrow(IdeProtocolError)
    expect(() => validateOpenResponse(ENV, {
      ...request,
      expectedBindingEpoch: '20',
    }, response)).toThrow(IdeProtocolError)
    // 规范 UUID 仍可能属于旧连接，不能把它发布为当前连接的授权。
    expect(() => validateOpenResponse(ENV, request, {
      ...response, connectionId: 'f2000000-0000-4000-8000-000000000003',
    })).toThrow(IdeProtocolError)
    expect(() => validateOpenResponse(ENV, request, {
      ...response, eventNodeId: 'f1000000-0000-4000-8000-000000000003',
    })).toThrow(IdeProtocolError)
  })

  it('accepts a zero-root OPEN receipt and command receipt without directory', () => {
    const request = buildOpenRequest(pending(), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      heartbeatIntervalMillis: '20000',
    })
    const entryUrl = `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`
    const nullDirectory: IdeWireView = {
      ...view({ entryUrl }),
      directory: null,
    }
    const omittedDirectory: IdeWireView = { ...viewWithoutDirectory(), entryUrl }

    for (const response of [nullDirectory, omittedDirectory]) {
      const opened = validateOpenResponse(ENV, request, response)
      expect(opened.workspacePath).toBe('/workspace/a|b')
      expect(opened.directory).toBeUndefined()

      const commanded = validateCommandView(ENV, BROWSER, VIEW, response)
      expect(commanded.workspacePath).toBe('/workspace/a|b')
      expect(commanded.directory).toBeUndefined()
    }
  })

  it('freezes the OPEN request from pending and only includes create directory', () => {
    const resume = buildOpenRequest(pending(), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      heartbeatIntervalMillis: '20000',
    })
    expect(resume).toMatchObject({
      viewId: VIEW,
      browserSessionId: BROWSER,
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      claimId: CLAIM,
      mode: 'resume',
      expectedBindingEpoch: '10',
      expectedDaemonInstanceId: DAEMON,
    })
    expect(resume).not.toHaveProperty('directory')

    const create = buildOpenRequest(pending({
      mode: 'create',
      expectedBindingEpoch: '0',
      directory: '/workspace/new',
    }), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      heartbeatIntervalMillis: '20000',
    })
    expect(create).toMatchObject({
      mode: 'create',
      expectedBindingEpoch: '0',
      directory: '/workspace/new',
    })

    const zeroRoot = buildOpenRequest(pending({
      mode: 'create',
      expectedBindingEpoch: '0',
      directory: null,
    }), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
      heartbeatIntervalMillis: '20000',
    })
    expect(zeroRoot).not.toHaveProperty('directory')
  })
})
