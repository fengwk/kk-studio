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
const VIEW_ID_B = 'c2000000-0000-4000-8000-000000000002'
const DAEMON_ID = 'd1000000-0000-4000-8000-000000000001'
const DAEMON_ID_B = 'd2000000-0000-4000-8000-000000000002'
const CLAIM_ID = 'e1000000-0000-4000-8000-000000000001'
const CLAIM_ID_B = 'e2000000-0000-4000-8000-000000000002'

function readyView(viewId = VIEW_ID, daemonInstanceId = DAEMON_ID, claimId = CLAIM_ID): IdeViewState {
  return {
    environmentId: ENVIRONMENT_ID,
    viewId,
    mode: 'resume',
    status: 'ready',
    claimId,
    attempt: 1,
    daemonInstanceId,
    bindingEpoch: '7',
    iframeLoaded: true,
    pendingShell: false,
  }
}

function useSessionRef() {
  return useRef<IdeBrowserSessionState>({
    browserSessionId: 'b1000000-0000-4000-8000-000000000001',
    status: 'ready',
    sessionSaved: true,
  })
}

describe('useIdeCloseCommands', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('closes one view independently when two views share an Environment', async () => {
    const viewA = readyView()
    const viewB = readyView(VIEW_ID_B, DAEMON_ID_B, CLAIM_ID_B)
    const commitViews = vi.fn()
    closeIdeView.mockResolvedValueOnce(undefined)
    const { result } = renderHook(() => {
      const viewsRef = useRef<Record<string, IdeViewState>>({ [VIEW_ID]: viewA, [VIEW_ID_B]: viewB })
      return useIdeCloseCommands({
        viewsRef,
        sessionRef: useSessionRef(),
        updateView: (viewId, attempt, patch) => {
          const view = viewsRef.current[viewId]
          if (view == null || view.attempt !== attempt) {
            return null
          }
          const updated = { ...view, ...patch }
          viewsRef.current = { ...viewsRef.current, [viewId]: updated }
          return updated
        },
        commitViews,
        forgetView: vi.fn(),
        writeSession: () => true,
        registerUsageNow: async () => undefined,
        registerUsageQuiet: vi.fn(),
      })
    })

    let resultValue!: Awaited<ReturnType<typeof result.current.closeView>>
    await act(async () => {
      resultValue = await result.current.closeView(VIEW_ID, false)
    })

    expect(resultValue.ok).toBe(true)
    expect(closeIdeView).toHaveBeenCalledWith(ENVIRONMENT_ID, VIEW_ID, expect.anything())
    expect(commitViews).toHaveBeenCalledWith({ [VIEW_ID_B]: viewB })
  })

  it('asks for explicit force confirmation only for outcome-unknown or missing control channel', async () => {
    const { result } = renderHook(() => {
      const viewsRef = useRef<Record<string, IdeViewState>>({ [VIEW_ID]: readyView() })
      return useIdeCloseCommands({
        viewsRef,
        sessionRef: useSessionRef(),
        updateView: (viewId, attempt, patch) => {
          const view = viewsRef.current[viewId]
          if (view == null || view.attempt !== attempt) {
            return null
          }
          const updated = { ...view, ...patch }
          viewsRef.current = { ...viewsRef.current, [viewId]: updated }
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
    let controlResult!: Awaited<ReturnType<typeof result.current.closeView>>
    await act(async () => {
      controlResult = await result.current.closeView(VIEW_ID, false)
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
    let unavailableResult!: Awaited<ReturnType<typeof result.current.closeView>>
    await act(async () => {
      unavailableResult = await result.current.closeView(VIEW_ID, false)
    })
    expect(unavailableResult).toMatchObject({ ok: false, requiresForceConfirmation: false })

    closeIdeView.mockRejectedValueOnce(
      new ApiError('unknown', 503, 'unavailable', undefined, 'IDE_OUTCOME_UNKNOWN'),
    )
    let unknownResult!: Awaited<ReturnType<typeof result.current.closeView>>
    await act(async () => {
      unknownResult = await result.current.closeView(VIEW_ID, false)
    })
    expect(unknownResult).toMatchObject({
      ok: false,
      requiresForceConfirmation: true,
      message: 'close-result-unknown',
    })
  })
})
