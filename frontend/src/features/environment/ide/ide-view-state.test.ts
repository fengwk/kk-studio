import { describe, expect, it } from 'vitest'
import {
  buildUsageViews,
  initialViews,
  recoveryFromViews,
  type IdeViewState,
} from '@/features/environment/ide/ide-view-state'
import type { IdeRecovery } from '@/features/environment/ide/ide-session-state'

const ENV = 'a1000000-0000-4000-8000-000000000001'
const VIEW_A = 'c1000000-0000-4000-8000-000000000001'
const VIEW_B = 'c2000000-0000-4000-8000-000000000002'
const DAEMON_A = 'd1000000-0000-4000-8000-000000000001'
const DAEMON_B = 'd2000000-0000-4000-8000-000000000002'
const CLAIM_A = 'e1000000-0000-4000-8000-000000000001'
const CLAIM_B = 'e2000000-0000-4000-8000-000000000002'

function recovery(viewId: string, daemonInstanceId: string): IdeRecovery {
  return { environmentId: ENV, viewId, daemonInstanceId, bindingEpoch: '2' }
}

function view(viewId: string, claimId: string, daemonInstanceId: string): IdeViewState {
  return {
    environmentId: ENV,
    viewId,
    mode: 'resume',
    status: 'ready',
    claimId,
    attempt: 0,
    daemonInstanceId,
    bindingEpoch: '2',
    iframeLoaded: true,
    pendingShell: false,
    entryUrl: `/ide/${ENV}/${viewId}`,
  }
}

describe('multi-view state', () => {
  it('restores and persists distinct identities for views grouped in one Environment', () => {
    const restored = initialViews({
      [VIEW_A]: recovery(VIEW_A, DAEMON_A),
      [VIEW_B]: recovery(VIEW_B, DAEMON_B),
    })

    expect(Object.keys(restored)).toEqual([VIEW_A, VIEW_B])
    expect(restored[VIEW_A]?.environmentId).toBe(ENV)
    expect(restored[VIEW_B]?.environmentId).toBe(ENV)
    expect(recoveryFromViews(restored)).toEqual({
      [VIEW_A]: recovery(VIEW_A, DAEMON_A),
      [VIEW_B]: recovery(VIEW_B, DAEMON_B),
    })
  })

  it('keeps hidden ready views in usage proof and applies a claim override only to its view', () => {
    const views = {
      [VIEW_A]: view(VIEW_A, CLAIM_A, DAEMON_A),
      [VIEW_B]: view(VIEW_B, CLAIM_B, DAEMON_B),
    }
    expect(buildUsageViews(views)).toEqual([
      { environmentId: ENV, viewId: VIEW_A, claimId: CLAIM_A, bindingEpoch: '2' },
      { environmentId: ENV, viewId: VIEW_B, claimId: CLAIM_B, bindingEpoch: '2' },
    ])
    expect(buildUsageViews(views, {
      environmentId: ENV,
      viewId: VIEW_B,
      claimId: 'e3000000-0000-4000-8000-000000000003',
      bindingEpoch: '3',
    })).toEqual([
      { environmentId: ENV, viewId: VIEW_A, claimId: CLAIM_A, bindingEpoch: '2' },
      {
        environmentId: ENV,
        viewId: VIEW_B,
        claimId: 'e3000000-0000-4000-8000-000000000003',
        bindingEpoch: '3',
      },
    ])
  })
})
