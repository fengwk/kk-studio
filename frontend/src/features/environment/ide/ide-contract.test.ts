import { describe, expect, it } from 'vitest'
import type {
  EnvironmentIdeViewDTO,
  EnvironmentIdeViewSnapshotDTO,
} from '@/shared/api/generated/contracts'
import {
  buildOpenRequest,
  canonicalJson,
  IdeProtocolError,
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

function snapshot(overrides: Partial<EnvironmentIdeViewSnapshotDTO> = {}): EnvironmentIdeViewSnapshotDTO {
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
    const response = view({
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    })
    expect(validateOpenResponse(ENV, BROWSER, pending(), response)).toMatchObject({
      viewId: VIEW,
      daemonInstanceId: DAEMON,
      bindingEpoch: '10',
    })
    expect(() => validateOpenResponse(ENV, BROWSER, pending(), view({
      claimId: 'e2000000-0000-4000-8000-000000000002',
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    }))).toThrow(IdeProtocolError)
    expect(() => validateOpenResponse(ENV, BROWSER, pending(), view({
      daemonInstanceId: 'd2000000-0000-4000-8000-000000000002',
      entryUrl: `/ide/${ENV}/${VIEW}/__ide/bootstrap?token=t`,
    }))).toThrow(IdeProtocolError)
    expect(() => validateOpenResponse(ENV, BROWSER, pending({
      expectedBindingEpoch: '20',
    }), response)).toThrow(IdeProtocolError)
  })

  it('freezes the OPEN request from pending and only includes create directory', () => {
    const resume = buildOpenRequest(pending(), BROWSER, {
      eventNodeId: EVENT_NODE,
      connectionId: CONNECTION,
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
    })
    expect(create).toMatchObject({
      mode: 'create',
      expectedBindingEpoch: '0',
      directory: '/workspace/new',
    })
  })
})
