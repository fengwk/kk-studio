import { useRef } from 'react'
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { useIdeCloseCommands } from '@/features/environment/ide/useIdeCloseCommands'
import type { IdeViewState } from '@/features/environment/ide/ide-view-state'
import type { IdeBrowserSessionState } from '@/features/environment/ide/ide-browser-session'

const closeIdeView = vi.hoisted(() => vi.fn())
vi.mock('@/features/environment/ide/environment-ide-service', () => ({ closeIdeView }))

const ENVIRONMENT_ID = 'a1000000-0000-4000-8000-000000000001'
const VIEW_ID = 'c1000000-0000-4000-8000-000000000001'
const DAEMON_ID = 'd1000000-0000-4000-8000-000000000001'

function readyView(): IdeViewState {
  return {
    environmentId: ENVIRONMENT_ID,
    viewId: VIEW_ID,
    mode: 'resume',
    status: 'ready',
    claimId: 'claim-1',
    attempt: 1,
    daemonInstanceId: DAEMON_ID,
    bindingEpoch: '7',
    iframeLoaded: true,
    pendingShell: false,
  }
}

describe('useIdeCloseCommands', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('asks for explicit force confirmation only for outcome-unknown or missing control channel', async () => {
    const { result } = renderHook(() => {
      const viewsRef = useRef<Record<string, IdeViewState>>({ [ENVIRONMENT_ID]: readyView() })
      const sessionRef = useRef<IdeBrowserSessionState>({
        browserSessionId: 'b1000000-0000-4000-8000-000000000001',
        status: 'ready',
        sessionSaved: true,
      })
      return useIdeCloseCommands({
        viewsRef,
        sessionRef,
        updateView: (environmentId, attempt, patch) => {
          const view = viewsRef.current[environmentId]
          if (view == null || view.attempt !== attempt) {
            return null
          }
          const updated = { ...view, ...patch }
          viewsRef.current = { ...viewsRef.current, [environmentId]: updated }
          return updated
        },
        commitViews: (views) => { viewsRef.current = views },
        forgetView: vi.fn(),
        writeSession: () => true,
        registerUsageNow: async () => undefined,
        registerUsageQuiet: vi.fn(),
      })
    })

    closeIdeView.mockRejectedValueOnce(
      new ApiError('not sent', 503, 'unavailable', undefined, 'IDE_CONTROL_UNAVAILABLE'),
    )
    let controlResult!: Awaited<ReturnType<typeof result.current.closeEnvironment>>
    await act(async () => {
      controlResult = await result.current.closeEnvironment(ENVIRONMENT_ID, false)
    })
    expect(controlResult).toMatchObject({
      ok: false,
      requiresForceConfirmation: true,
      message: 'close-control-unavailable',
    })
    expect(closeIdeView.mock.calls[0]?.[2]).toMatchObject({ discard: false })

    closeIdeView.mockRejectedValueOnce(
      new ApiError('unavailable', 503, 'unavailable', undefined, 'IDE_UNAVAILABLE'),
    )
    let unavailableResult!: Awaited<ReturnType<typeof result.current.closeEnvironment>>
    await act(async () => {
      unavailableResult = await result.current.closeEnvironment(ENVIRONMENT_ID, false)
    })
    expect(unavailableResult).toMatchObject({ ok: false, requiresForceConfirmation: false })

    closeIdeView.mockRejectedValueOnce(
      new ApiError('unknown', 503, 'unavailable', undefined, 'IDE_OUTCOME_UNKNOWN'),
    )
    let unknownResult!: Awaited<ReturnType<typeof result.current.closeEnvironment>>
    await act(async () => {
      unknownResult = await result.current.closeEnvironment(ENVIRONMENT_ID, false)
    })
    expect(unknownResult).toMatchObject({
      ok: false,
      requiresForceConfirmation: true,
      message: 'close-result-unknown',
    })
  })
})
