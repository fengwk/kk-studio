import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type {
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
} from '@/shared/api/generated/contracts'
import {
  buildUsageViews,
  containsSensitivePath,
  ideClaimReducer,
  ideErrorDetail,
  ideErrorText,
  initialViews,
  isDirtyClose,
  isUnknownIdeFailure,
  isViewGone,
  isViewInUse,
  recoveryFromViews,
  safeIdeError,
  type IdeClaimAction,
  type IdeViewState,
} from '@/features/environment/ide/ide-claim-reducer'
import type {
  IdePresentation,
  IdeRecovery,
} from '@/features/environment/ide/ide-tab-state'

const ENV_1 = 'a1000000-0000-4000-8000-000000000001'
const ENV_2 = 'a1000000-0000-4000-8000-000000000002'
const VIEW_1 = 'b1000000-0000-4000-8000-000000000001'
const CLAIM_1 = 'c1000000-0000-4000-8000-000000000001'
const DAEMON_1 = 'd1000000-0000-4000-8000-000000000001'
const NODE_1 = 'e1000000-0000-4000-8000-000000000001'
const CONN_1 = 'f1000000-0000-4000-8000-000000000001'

function sampleView(overrides: Partial<IdeViewState> = {}): IdeViewState {
  return {
    environmentId: ENV_1,
    viewId: VIEW_1,
    claimId: CLAIM_1,
    mode: 'resume',
    status: 'ready',
    attempt: 1,
    claimInFlight: false,
    entryUrl: '/environment-ide/env-1/view-1/__ide/bootstrap?token=xyz',
    daemonInstanceId: DAEMON_1,
    bindingEpoch: '10',
    directory: '/workspace/project',
    workspacePath: '/workspace/project',
    iframeLoaded: true,
    pendingShell: false,
    ...overrides,
  }
}

describe('ide-claim-reducer helpers', () => {
  it('formats ideErrorText and ideErrorDetail', () => {
    expect(ideErrorText(new Error('plain error'))).toBe('plain error')
    expect(ideErrorText('raw string')).toBe('raw string')

    const apiErrorWithDetails = new ApiError('Failed to connect', 500, 'IDE_FAILED', {
      detail: 'Connection refused',
      extra: 'Port 8080 unreachable',
    })
    expect(ideErrorText(apiErrorWithDetails)).toContain('IDE_FAILED')
    expect(ideErrorText(apiErrorWithDetails)).toContain('Failed to connect')
    expect(ideErrorText(apiErrorWithDetails)).toContain('Connection refused')

    expect(ideErrorDetail(apiErrorWithDetails)).toBe('Connection refused')
    expect(ideErrorDetail(new ApiError('Msg', 400, 'NO_DETAIL'))).toBe('Msg')
    expect(ideErrorDetail(new Error('error msg'))).toBe('error msg')
    expect(ideErrorDetail(123)).toBe('')
  })

  it('detects sensitive paths and sanitizes safeIdeError', () => {
    expect(containsSensitivePath('/home/user/code')).toBe(true)
    expect(containsSensitivePath('/var/run/ide')).toBe(true)
    expect(containsSensitivePath('/tmp/scratch')).toBe(true)
    expect(containsSensitivePath('/srv/data')).toBe(true)
    expect(containsSensitivePath('/Users/dev/repo')).toBe(true)
    expect(containsSensitivePath('C:\\workspace')).toBe(true)
    expect(containsSensitivePath('d:\\projects')).toBe(true)
    expect(containsSensitivePath('workspace/repo')).toBe(false)

    const sensitiveErr = new ApiError('fail', 400, 'ERR', { detail: '/home/user/error.log not found' })
    expect(safeIdeError(sensitiveErr)).toBe('')
    const emptyErr = new Error('')
    expect(safeIdeError(emptyErr)).toBe('')

    const safeErr = new ApiError('fail', 400, 'ERR', { detail: 'Service unavailable' })
    expect(safeIdeError(safeErr)).toBe('Service unavailable')
    expect(safeIdeError(new Error('generic'))).toBe('generic')
  })

  it('classifies error categories', () => {
    const notFoundApi = new ApiError('Not found', 404, 'NOT_FOUND')
    expect(isViewGone(notFoundApi)).toBe(true)
    expect(isViewGone(new Error('VIEW_GONE'))).toBe(true)
    expect(isViewGone(new Error('other'))).toBe(false)

    expect(isViewInUse(new Error('VIEW_IN_USE'))).toBe(true)
    expect(isViewInUse(new Error('CLAIM_CONFLICT'))).toBe(true)
    expect(isViewInUse(new Error('other'))).toBe(false)

    expect(isDirtyClose(new Error('DIRTY state'))).toBe(true)
    expect(isDirtyClose(new Error('has unsaved changes'))).toBe(true)
    expect(isDirtyClose(new Error('save or discard'))).toBe(true)
    expect(isDirtyClose(new Error('clean'))).toBe(false)

    const server500 = new ApiError('Internal', 500, 'SERVER_ERROR', {})
    expect(isUnknownIdeFailure(server500)).toBe(true)
    const noStatus = new ApiError('No status', undefined, 'NO_STATUS')
    expect(isUnknownIdeFailure(noStatus)).toBe(true)
    const unavail = new ApiError('Unavail', 503, 'IDE_UNAVAILABLE', {})
    expect(isUnknownIdeFailure(unavail)).toBe(true)
    const badReq = new ApiError('Invalid param', 400, 'BAD_REQUEST', {})
    expect(isUnknownIdeFailure(badReq)).toBe(false)
    expect(isUnknownIdeFailure(new Error('network error'))).toBe(true)
  })

  it('builds initialViews from presentation and recovery', () => {
    const presentation: IdePresentation = {
      panelVisible: true,
      selectedEnvironmentId: ENV_1,
      openedEnvironmentIds: [ENV_1, ENV_2],
    }
    const recovery: Record<string, IdeRecovery> = {
      [ENV_1]: { viewId: VIEW_1, daemonInstanceId: DAEMON_1, directory: '/src' },
    }
    const views = initialViews(presentation, recovery)
    expect(Object.keys(views)).toEqual([ENV_1])
    expect(views[ENV_1]).toEqual({
      environmentId: ENV_1,
      viewId: VIEW_1,
      claimId: '',
      mode: 'resume',
      status: 'pending',
      attempt: 0,
      claimInFlight: false,
      daemonInstanceId: DAEMON_1,
      directory: '/src',
      iframeLoaded: false,
      pendingShell: false,
    })
  })

  it('extracts recoveryFromViews correctly', () => {
    const views: Record<string, IdeViewState> = {
      [ENV_1]: sampleView({ directory: '/valid' }),
      [ENV_2]: sampleView({ environmentId: ENV_2, viewId: 'invalid-id' }),
    }
    const recovery = recoveryFromViews(views, [ENV_1, ENV_2])
    expect(recovery[ENV_1]).toEqual({
      viewId: VIEW_1,
      daemonInstanceId: DAEMON_1,
      directory: '/valid',
    })
    expect(recovery[ENV_2]).toBeUndefined()
  })

  it('builds usage views only for active/reconciling views', () => {
    const views: Record<string, IdeViewState> = {
      [ENV_1]: sampleView({ status: 'ready', bindingEpoch: '10' }),
      [ENV_2]: sampleView({ environmentId: ENV_2, status: 'gone' }),
    }
    const usage = buildUsageViews(views, [ENV_1, ENV_2])
    expect(usage).toHaveLength(1)
    expect(usage[0]).toEqual({
      environmentId: ENV_1,
      viewId: VIEW_1,
      claimId: CLAIM_1,
      bindingEpoch: '10',
    })

    // Views without bindingEpoch
    const viewsRegistering: Record<string, IdeViewState> = {
      [ENV_1]: sampleView({ status: 'registering', bindingEpoch: undefined }),
    }
    const usage2 = buildUsageViews(viewsRegistering, [ENV_1])
    expect(usage2).toHaveLength(1)
    expect(usage2[0].bindingEpoch).toBeUndefined()
  })
})

describe('ideClaimReducer actions', () => {
  it('handles BEGIN_CLAIM for create and resume', () => {
    // Fresh creation
    const state0: Record<string, IdeViewState> = {}
    const state1 = ideClaimReducer(state0, {
      type: 'BEGIN_CLAIM',
      environmentId: ENV_1,
      spec: {
        mode: 'create',
        viewId: VIEW_1,
        claimId: CLAIM_1,
        directory: '/workspace/project',
        pendingShell: true,
      },
    })
    expect(state1[ENV_1].attempt).toBe(1)
    expect(state1[ENV_1].status).toBe('registering')
    expect(state1[ENV_1].claimInFlight).toBe(true)
    expect(state1[ENV_1].directory).toBe('/workspace/project')
    expect(state1[ENV_1].pendingShell).toBe(true)

    // Resume on existing view with entryUrl
    const state2 = ideClaimReducer(
      { [ENV_1]: sampleView({ entryUrl: 'http://localhost/view' }) },
      {
        type: 'BEGIN_CLAIM',
        environmentId: ENV_1,
        spec: {
          mode: 'resume',
          viewId: VIEW_1,
          claimId: CLAIM_1,
        },
      },
    )
    expect(state2[ENV_1].status).toBe('rebinding')
    expect(state2[ENV_1].attempt).toBe(2)
  })

  it('handles CLAIM_REQUEST_SUBMITTED', () => {
    const initial = { [ENV_1]: sampleView({ attempt: 2, status: 'registering' }) }
    const request: EnvironmentIdeOpenViewDTO = {
      viewId: VIEW_1,
      documentId: 'doc-1',
      eventNodeId: NODE_1,
      connectionId: CONN_1,
      claimId: CLAIM_1,
      mode: 'resume',
    }

    // Attempt mismatch ignored
    const unchanged = ideClaimReducer(initial, {
      type: 'CLAIM_REQUEST_SUBMITTED',
      environmentId: ENV_1,
      attempt: 1,
      request,
    })
    expect(unchanged).toBe(initial)

    // Attempt match updates to claiming
    const updated = ideClaimReducer(initial, {
      type: 'CLAIM_REQUEST_SUBMITTED',
      environmentId: ENV_1,
      attempt: 2,
      request,
    })
    expect(updated[ENV_1].status).toBe('claiming')
    expect(updated[ENV_1].claimRequest).toBe(request)
    expect(updated[ENV_1].claimRequestIdentity).toEqual({
      eventNodeId: NODE_1,
      connectionId: CONN_1,
    })
  })

  it('handles CLAIM_SUCCEEDED', () => {
    const initial = {
      [ENV_1]: sampleView({
        attempt: 3,
        status: 'claiming',
        entryUrl: '/old-url',
        iframeLoaded: true,
      }),
    }
    const response: EnvironmentIdeViewDTO = {
      viewId: VIEW_1,
      environmentId: ENV_1,
      documentId: 'doc-1',
      eventNodeId: NODE_1,
      connectionId: CONN_1,
      claimId: CLAIM_1,
      daemonInstanceId: DAEMON_1,
      bindingEpoch: '12',
      directory: '/new/dir',
      workspacePath: '/new/dir',
      state: 'OPEN',
      entryUrl: '/new-url',
    }

    // Mismatched attempt ignored
    expect(
      ideClaimReducer(initial, {
        type: 'CLAIM_SUCCEEDED',
        environmentId: ENV_1,
        attempt: 2,
        claimId: CLAIM_1,
        response,
        entryUrl: '/new-url',
      }),
    ).toBe(initial)

    // Matched attempt with changed entryUrl resets iframeLoaded
    const success = ideClaimReducer(initial, {
      type: 'CLAIM_SUCCEEDED',
      environmentId: ENV_1,
      attempt: 3,
      claimId: CLAIM_1,
      response,
      entryUrl: '/new-url',
    })
    expect(success[ENV_1].status).toBe('ready')
    expect(success[ENV_1].claimInFlight).toBe(false)
    expect(success[ENV_1].bindingEpoch).toBe('12')
    expect(success[ENV_1].directory).toBe('/new/dir')
    expect(success[ENV_1].iframeLoaded).toBe(false)

    // Same entryUrl preserves iframeLoaded
    const initialSameUrl = {
      [ENV_1]: sampleView({
        attempt: 3,
        status: 'claiming',
        entryUrl: '/same-url',
        iframeLoaded: true,
      }),
    }
    const successSame = ideClaimReducer(initialSameUrl, {
      type: 'CLAIM_SUCCEEDED',
      environmentId: ENV_1,
      attempt: 3,
      claimId: CLAIM_1,
      response,
      entryUrl: '/same-url',
    })
    expect(successSame[ENV_1].iframeLoaded).toBe(true)
  })

  it('handles CLAIM_FAILED with in-use, gone, unknown, and error', () => {
    const initial = { [ENV_1]: sampleView({ attempt: 2, status: 'claiming', claimInFlight: true }) }

    // in-use on resume preserves viewId, sets status in-use
    const inUse = ideClaimReducer(initial, {
      type: 'CLAIM_FAILED',
      environmentId: ENV_1,
      attempt: 2,
      mode: 'resume',
      error: new Error('VIEW_IN_USE'),
    })
    expect(inUse[ENV_1].status).toBe('in-use')
    expect(inUse[ENV_1].claimInFlight).toBe(false)

    // gone sets status gone
    const gone = ideClaimReducer(initial, {
      type: 'CLAIM_FAILED',
      environmentId: ENV_1,
      attempt: 2,
      mode: 'create',
      error: new Error('VIEW_GONE'),
    })
    expect(gone[ENV_1].status).toBe('gone')
    expect(gone[ENV_1].errorMessage).toBeUndefined()

    // unknown failure sets status unknown
    const unknown = ideClaimReducer(initial, {
      type: 'CLAIM_FAILED',
      environmentId: ENV_1,
      attempt: 2,
      mode: 'create',
      error: new ApiError('Unavailable', 503, 'IDE_UNAVAILABLE', { detail: 'Daemon down' }),
    })
    expect(unknown[ENV_1].status).toBe('unknown')
    expect(unknown[ENV_1].errorMessage).toBe('Daemon down')

    // generic error
    const generic = ideClaimReducer(initial, {
      type: 'CLAIM_FAILED',
      environmentId: ENV_1,
      attempt: 2,
      mode: 'create',
      error: new ApiError('Invalid', 400, 'BAD_CONFIG', { detail: 'Config bad' }),
    })
    expect(generic[ENV_1].status).toBe('error')
    expect(generic[ENV_1].errorMessage).toBe('Config bad')
  })

  it('handles CLAIM_VALIDATION_ERROR and RESET_CLAIM_FLIGHT', () => {
    const initial = { [ENV_1]: sampleView({ attempt: 4, claimInFlight: true }) }

    const valErr = ideClaimReducer(initial, {
      type: 'CLAIM_VALIDATION_ERROR',
      environmentId: ENV_1,
      attempt: 4,
      errorMessage: 'Invalid bootstrap token',
    })
    expect(valErr[ENV_1].status).toBe('error')
    expect(valErr[ENV_1].claimInFlight).toBe(false)
    expect(valErr[ENV_1].errorMessage).toBe('Invalid bootstrap token')

    const reset = ideClaimReducer(initial, {
      type: 'RESET_CLAIM_FLIGHT',
      environmentId: ENV_1,
      attempt: 4,
    })
    expect(reset[ENV_1].status).toBe('pending')
    expect(reset[ENV_1].claimInFlight).toBe(false)
  })

  it('handles SET_OFFLINE', () => {
    const pending = { [ENV_1]: sampleView({ status: 'pending' }) }
    const offline = ideClaimReducer(pending, { type: 'SET_OFFLINE', environmentId: ENV_1 })
    expect(offline[ENV_1].status).toBe('offline')

    // already offline returns same state
    expect(ideClaimReducer(offline, { type: 'SET_OFFLINE', environmentId: ENV_1 })).toBe(offline)

    // ready status not changed
    const ready = { [ENV_1]: sampleView({ status: 'ready' }) }
    expect(ideClaimReducer(ready, { type: 'SET_OFFLINE', environmentId: ENV_1 })).toBe(ready)
  })

  it('handles terminal lifecycle', () => {
    const ready = { [ENV_1]: sampleView({ bindingEpoch: '10', pendingShell: false }) }

    const withShell = ideClaimReducer(ready, { type: 'SET_PENDING_SHELL', environmentId: ENV_1 })
    expect(withShell[ENV_1].pendingShell).toBe(true)
    expect(withShell[ENV_1].terminalError).toBeUndefined()

    const submitted = ideClaimReducer(withShell, { type: 'TERMINAL_SUBMITTED', environmentId: ENV_1 })
    expect(submitted[ENV_1].pendingShell).toBe(false)

    // Terminal failed on mismatched epoch ignored
    const mismatched = ideClaimReducer(submitted, {
      type: 'TERMINAL_FAILED',
      environmentId: ENV_1,
      bindingEpoch: '9',
      terminalError: 'terminal-failed',
    })
    expect(mismatched).toBe(submitted)

    // Terminal failed on matched epoch sets terminalError
    const failed = ideClaimReducer(submitted, {
      type: 'TERMINAL_FAILED',
      environmentId: ENV_1,
      bindingEpoch: '10',
      terminalError: 'terminal-failed',
    })
    expect(failed[ENV_1].terminalError).toBe('terminal-failed')
  })

  it('handles DIRECTORY_CHANGED with epoch fencing', () => {
    const initial = { [ENV_1]: sampleView({ bindingEpoch: '15', directory: '/old' }) }

    // Mismatched epoch ignored
    expect(
      ideClaimReducer(initial, {
        type: 'DIRECTORY_CHANGED',
        environmentId: ENV_1,
        bindingEpoch: '14',
        directory: '/new',
      }),
    ).toBe(initial)

    // Matched epoch updates directory and workspacePath
    const updated = ideClaimReducer(initial, {
      type: 'DIRECTORY_CHANGED',
      environmentId: ENV_1,
      bindingEpoch: '15',
      directory: '/new',
      workspacePath: '/new/ws',
    })
    expect(updated[ENV_1].directory).toBe('/new')
    expect(updated[ENV_1].workspacePath).toBe('/new/ws')
  })

  it('handles IFRAME_LOADED with entryUrl check', () => {
    const initial = { [ENV_1]: sampleView({ entryUrl: '/app', iframeLoaded: false }) }

    // Different entryUrl ignored
    expect(
      ideClaimReducer(initial, {
        type: 'IFRAME_LOADED',
        environmentId: ENV_1,
        entryUrl: '/different',
      }),
    ).toBe(initial)

    // Matching entryUrl sets iframeLoaded true
    const loaded = ideClaimReducer(initial, {
      type: 'IFRAME_LOADED',
      environmentId: ENV_1,
      entryUrl: '/app',
    })
    expect(loaded[ENV_1].iframeLoaded).toBe(true)

    // Already loaded returns same state
    expect(
      ideClaimReducer(loaded, {
        type: 'IFRAME_LOADED',
        environmentId: ENV_1,
        entryUrl: '/app',
      }),
    ).toBe(loaded)
  })

  it('handles close lifecycle and error dismissal', () => {
    const initial = { [ENV_1]: sampleView() }

    // Non-existent view ignored
    expect(ideClaimReducer(initial, { type: 'CLOSE_SUBMITTED', environmentId: 'non-existent' })).toBe(initial)
    expect(ideClaimReducer(initial, { type: 'CLOSE_FAILED', environmentId: 'non-existent', dirty: false })).toBe(initial)
    expect(ideClaimReducer(initial, { type: 'DISMISS_CLOSE_ERROR', environmentId: 'non-existent' })).toBe(initial)

    const closeSub = ideClaimReducer(initial, { type: 'CLOSE_SUBMITTED', environmentId: ENV_1 })
    expect(closeSub[ENV_1].closeInFlight).toBe(true)

    // Dirty close failure clears closeError
    const dirtyFail = ideClaimReducer(closeSub, {
      type: 'CLOSE_FAILED',
      environmentId: ENV_1,
      dirty: true,
      closeError: 'some error',
    })
    expect(dirtyFail[ENV_1].closeInFlight).toBe(false)
    expect(dirtyFail[ENV_1].closeError).toBeUndefined()

    // Non-dirty close failure records closeError
    const nonDirtyFail = ideClaimReducer(closeSub, {
      type: 'CLOSE_FAILED',
      environmentId: ENV_1,
      dirty: false,
      closeError: 'close-failed',
    })
    expect(nonDirtyFail[ENV_1].closeInFlight).toBe(false)
    expect(nonDirtyFail[ENV_1].closeError).toBe('close-failed')

    // Dismiss close error
    const dismissed = ideClaimReducer(nonDirtyFail, {
      type: 'DISMISS_CLOSE_ERROR',
      environmentId: ENV_1,
    })
    expect(dismissed[ENV_1].closeError).toBeUndefined()
  })

  it('handles REMOVE_VIEW, PRUNE_VIEWS, and REPLACE_VIEW', () => {
    const initial = {
      [ENV_1]: sampleView(),
      [ENV_2]: sampleView({ environmentId: ENV_2 }),
    }

    const removed = ideClaimReducer(initial, { type: 'REMOVE_VIEW', environmentId: ENV_1 })
    expect(Object.keys(removed)).toEqual([ENV_2])

    // Removing non-existent view returns same state
    expect(ideClaimReducer(removed, { type: 'REMOVE_VIEW', environmentId: ENV_1 })).toBe(removed)

    // Prune views
    const pruned = ideClaimReducer(initial, {
      type: 'PRUNE_VIEWS',
      availableEnvironmentIds: new Set([ENV_1]),
    })
    expect(Object.keys(pruned)).toEqual([ENV_1])

    // Replace view
    const replacement = sampleView({ directory: '/custom' })
    const replaced = ideClaimReducer(initial, {
      type: 'REPLACE_VIEW',
      environmentId: ENV_1,
      view: replacement,
    })
    expect(replaced[ENV_1].directory).toBe('/custom')

    // Default returns same state
    expect(ideClaimReducer(initial, { type: 'UNKNOWN_ACTION' } as unknown as IdeClaimAction)).toBe(
      initial,
    )
  })
})
