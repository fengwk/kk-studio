import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApplicationEventConnectionState } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { environmentService } from '@/features/environment/environment-service'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { openIdeAttachment } from '@/features/environment/ide/environment-ide-service'
import { isBoundToConnection, isSameBinding, type IdeViewState } from '@/features/environment/ide/ide-view-state'
import { CANONICAL_POSITIVE_DECIMAL } from '@/features/environment/ide/ide-decimal'
import {
  IdePanelContext,
  type IdeEnvironmentInfo,
  type IdeOpenEnvironmentRequest,
} from '@/features/environment/ide/ide-panel-context'
import {
  initializeIdeTabState,
  saveIdePresentation,
  type IdePresentation,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import { IdeOverlayPanel, type IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'
import { IdeSplitShell } from '@/features/environment/ide/IdeSplitShell'
import { recoveryFromViews } from '@/features/environment/ide/ide-view-state'
import { useIdeClaimReconciliation } from '@/features/environment/ide/useIdeClaimReconciliation'
import { useIdeBrowserSession } from '@/features/environment/ide/ide-browser-session'

interface IdePanelProviderProps {
  storage?: IdeTabStorage
  /** 仅 `/chats/:chatId` 时为 true；宿主离开 Chat 后保持 DOM 但 hidden + inert。 */
  active: boolean
}

function toEnvironmentInfo(environment: EnvironmentDTO): IdeEnvironmentInfo {
  return { id: environment.id, name: environment.name, ready: environment.ready }
}

function clearRuntimePresentation(presentation: IdePresentation): IdePresentation {
  return {
    panelVisible: presentation.panelVisible,
    selectedEnvironmentId: null,
    openedEnvironmentIds: [],
    viewIdsByEnvironment: {},
    selectedViewIdsByEnvironment: {},
  }
}

/**
 * Chat IDE 持久宿主：组合 Context、浏览器会话锁与恢复状态机。
 *
 * <p>该组件在应用组合层常驻；只有 Chat 详情路由激活它。离开 Chat 时宿主 hidden + inert，
 * iframe 不移根、不卸载。视图状态机只按权威快照与结构化 reason 推进。
 */
export function IdePanelProvider({
  children,
  storage,
  active,
}: PropsWithChildren<IdePanelProviderProps>) {
  const queryClient = useQueryClient()
  const connectionState = useApplicationEventConnectionState()
  const [initial] = useState(() => initializeIdeTabState(storage))
  const [presentation, setPresentationState] = useState<IdePresentation>(initial.presentation)
  const [activeEnvironmentId, setActiveEnvironmentId] = useState<string | null>(
    () => (active ? initial.presentation.selectedEnvironmentId : null),
  )
  const activeEnvironmentIdRef = useRef(activeEnvironmentId)
  const [knownEnvironments, setKnownEnvironments] = useState<Record<string, IdeEnvironmentInfo>>({})
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved && !initial.recoveryError)

  const presentationRef = useRef(presentation)
  const actionsRef = useRef<{
    openEnvironment: (request: IdeOpenEnvironmentRequest) => void
  } | null>(null)
  const cloneHandlerRef = useRef<(browserSessionId: string) => void>(() => {})
  const restoreHandlerRef = useRef<() => void>(() => {})
  const suspendHandlerRef = useRef<() => void>(() => {})

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: active || presentation.openedEnvironmentIds.length > 0,
  })

  const invalidateEnvironments = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }, [queryClient])

  useResourceSync({ kind: 'environments' }, {
    sync: invalidateEnvironments,
    syncOn: (name) => name === 'changed',
  })

  const environments = useMemo(() => {
    const map = new Map<string, IdeEnvironmentInfo>()
    for (const listed of environmentsQuery.data ?? []) {
      map.set(listed.id, toEnvironmentInfo(listed))
    }
    for (const environment of Object.values(knownEnvironments)) {
      if (!map.has(environment.id)) {
        map.set(environment.id, environment)
      }
    }
    return [...map.values()].sort((left, right) => left.name.localeCompare(right.name))
  }, [environmentsQuery.data, knownEnvironments])

  const environmentMap = useMemo(() => {
    return new Map(environments.map((environment) => [environment.id, environment]))
  }, [environments])
  const environmentMapRef = useRef(environmentMap)

  useEffect(() => {
    environmentMapRef.current = environmentMap
  }, [environmentMap])

  function rememberEnvironment(environment: IdeEnvironmentInfo) {
    setKnownEnvironments((current) => {
      const existing = current[environment.id]
      if (
        existing != null
        && existing.name === environment.name
        && existing.ready === environment.ready
      ) {
        return current
      }
      return { ...current, [environment.id]: environment }
    })
  }

  function setActiveEnvironment(environmentId: string | null): void {
    activeEnvironmentIdRef.current = environmentId
    setActiveEnvironmentId(environmentId)
  }

  const getPresentation = useCallback(() => presentationRef.current, [])
  const getEnvironmentMap = useCallback(() => environmentMapRef.current, [])

  const handleStorageStatusChange = useCallback((unavailable: boolean) => {
    setStorageUnavailable(unavailable)
  }, [])

  const session = useIdeBrowserSession({
    initial,
    storage,
    onClone: (browserSessionId) => cloneHandlerRef.current(browserSessionId),
    onRestore: () => restoreHandlerRef.current(),
    onSuspend: () => suspendHandlerRef.current(),
  })

  const claimReconciliation = useIdeClaimReconciliation({
    storage,
    initial,
    session,
    activeEnvironmentId,
    getPresentation,
    getEnvironmentMap,
    onStorageStatusChange: handleStorageStatusChange,
    onViewCreated: (environmentId, viewId) => {
      const current = presentationRef.current
      const ids = current.viewIdsByEnvironment[environmentId] ?? []
      commitPresentation({
        ...current,
        viewIdsByEnvironment: ids.includes(viewId)
          ? current.viewIdsByEnvironment
          : { ...current.viewIdsByEnvironment, [environmentId]: [...ids, viewId] },
        selectedViewIdsByEnvironment: current.selectedEnvironmentId === environmentId
          ? { ...current.selectedViewIdsByEnvironment, [environmentId]: viewId }
          : current.selectedViewIdsByEnvironment,
      }, 'session')
    },
  })

  useEffect(() => {
    cloneHandlerRef.current = (browserSessionId) => {
      claimReconciliation.resetForClone()
      const next = clearRuntimePresentation(presentationRef.current)
      presentationRef.current = next
      setPresentationState(next)
      setActiveEnvironment(null)
      if (!initial.recoveryError) {
        const saved = saveIdePresentation(
          browserSessionId,
          next,
          {},
          storage,
        )
        setStorageUnavailable(!saved.sessionSaved)
      }
    }
    restoreHandlerRef.current = () => {
      claimReconciliation.restoreUsage()
      claimReconciliation.reconcileAll()
    }
    suspendHandlerRef.current = () => {
      claimReconciliation.withdrawUsage()
    }
  })

  const commitPresentation = useCallback((
    next: IdePresentation,
    write: 'both' | 'session' | 'none' = 'both',
  ) => {
    presentationRef.current = next
    setPresentationState(next)
    if (initial.recoveryError) {
      return
    }
    if (write === 'both') {
      const saved = saveIdePresentation(
        session.browserSessionId,
        next,
        recoveryFromViews(claimReconciliation.viewsRef.current),
        storage,
      )
      setStorageUnavailable(!saved.sessionSaved)
    } else if (write === 'session') {
      claimReconciliation.writeSession()
    }
  }, [claimReconciliation, initial.recoveryError, session.browserSessionId, storage])

  useEffect(() => {
    const current = presentationRef.current
    const viewIdsByEnvironment: Record<string, string[]> = {}
    for (const view of Object.values(claimReconciliation.views)) {
      const ids = viewIdsByEnvironment[view.environmentId] ?? []
      ids.push(view.viewId)
      viewIdsByEnvironment[view.environmentId] = ids
    }
    for (const [environmentId, ids] of Object.entries(viewIdsByEnvironment)) {
      const previousIds = current.viewIdsByEnvironment[environmentId] ?? []
      ids.sort((left, right) => {
        const leftRank = previousIds.indexOf(left)
        const rightRank = previousIds.indexOf(right)
        return leftRank < 0 && rightRank < 0 ? left.localeCompare(right)
          : leftRank < 0 ? 1 : rightRank < 0 ? -1 : leftRank - rightRank
      })
    }
    const selectedViewIdsByEnvironment: Record<string, string> = {}
    for (const [environmentId, ids] of Object.entries(viewIdsByEnvironment)) {
      const previous = current.selectedViewIdsByEnvironment[environmentId]
      const selected = previous != null && ids.includes(previous) ? previous : ids[0]
      if (selected != null) {
        selectedViewIdsByEnvironment[environmentId] = selected
      }
    }
    if (
      JSON.stringify(selectedViewIdsByEnvironment) !== JSON.stringify(current.selectedViewIdsByEnvironment)
      || JSON.stringify(viewIdsByEnvironment) !== JSON.stringify(current.viewIdsByEnvironment)
    ) {
      commitPresentation({ ...current, viewIdsByEnvironment, selectedViewIdsByEnvironment }, 'session')
    }
  }, [claimReconciliation.views, commitPresentation])

  useEffect(() => {
    if (!active) {
      setActiveEnvironment(null)
    }
  }, [active])

  function selectedViewForEnvironment(presentation: IdePresentation, environmentId: string): string | null {
    const remembered = presentation.selectedViewIdsByEnvironment[environmentId]
    if (remembered != null
      && claimReconciliation.viewsRef.current[remembered]?.environmentId === environmentId) {
      return remembered
    }
    return presentation.viewIdsByEnvironment[environmentId]?.find(
      (id) => claimReconciliation.viewsRef.current[id]?.environmentId === environmentId,
    ) ?? null
  }

  function ensureOpened(
    current: IdePresentation,
    environmentId: string,
    panelVisible: boolean,
  ): IdePresentation {
    const openedEnvironmentIds = current.openedEnvironmentIds.includes(environmentId)
      ? current.openedEnvironmentIds
      : [...current.openedEnvironmentIds, environmentId]
    const selectedViewId = selectedViewForEnvironment(current, environmentId)
    const selectedViewIdsByEnvironment = { ...current.selectedViewIdsByEnvironment }
    if (selectedViewId == null) {
      delete selectedViewIdsByEnvironment[environmentId]
    } else {
      selectedViewIdsByEnvironment[environmentId] = selectedViewId
    }
    if (
      current.panelVisible === panelVisible
      && current.selectedEnvironmentId === environmentId
      && openedEnvironmentIds === current.openedEnvironmentIds
      && selectedViewIdsByEnvironment[environmentId] === current.selectedViewIdsByEnvironment[environmentId]
    ) {
      return current
    }
    return {
      ...current,
      panelVisible,
      selectedEnvironmentId: environmentId,
      openedEnvironmentIds,
      selectedViewIdsByEnvironment,
    }
  }

  function activateChatEnvironment(environment: IdeEnvironmentInfo | null): void {
    if (environment == null || !isCanonicalUuid(environment.id)) {
      setActiveEnvironment(null)
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironment(environment.id)
    const current = presentationRef.current
    const existingViewId = selectedViewForEnvironment(current, environment.id)
    if (existingViewId == null) {
      // Chat 选择不是打开操作；不把未打开的环境写成已打开面板的选择。
      return
    }
    commitPresentation(ensureOpened(current, environment.id, current.panelVisible), 'both')
    const viewId = claimReconciliation.startOrFocus(environment.id, environment, 'files')
    if (viewId != null) {
      persistViewId(environment.id, viewId)
    }
  }

  function selectedReadyAttachmentView(environmentId: string): IdeViewState | null {
    if (
      !active
      || initial.recoveryError
      || activeEnvironmentIdRef.current !== environmentId
      || presentationRef.current.selectedEnvironmentId !== environmentId
    ) {
      return null
    }
    const viewId = selectedViewForEnvironment(presentationRef.current, environmentId)
    const view = viewId == null ? null : claimReconciliation.viewsRef.current[viewId] ?? null
    const identity = claimReconciliation.identity
    if (
      view == null
      || view.environmentId !== environmentId
      || view.status !== 'ready'
      || view.closeInFlight
      || view.daemonInstanceId == null
      || !isCanonicalUuid(view.daemonInstanceId)
      || view.bindingEpoch == null
      || !CANONICAL_POSITIVE_DECIMAL.test(view.bindingEpoch)
      || identity == null
      || !isBoundToConnection(view, identity.eventNodeId, identity.connectionId)
    ) {
      return null
    }
    return view
  }

  function canOpenAttachment(environmentId: string): boolean {
    return selectedReadyAttachmentView(environmentId) != null
  }

  async function openAttachment(
    environmentId: string,
    sessionId: string,
    resourceId: string,
  ): Promise<void> {
    const view = selectedReadyAttachmentView(environmentId)
    if (view == null || !isCanonicalUuid(sessionId) || !isCanonicalUuid(resourceId)) {
      throw new Error('IDE view is not ready for this attachment')
    }
    const current = claimReconciliation.viewsRef.current[view.viewId]
    if (!isSameBinding(current, view)) {
      throw new Error('IDE view binding changed')
    }
    commitPresentation(ensureOpened(presentationRef.current, environmentId, true), 'both')
    const body = {
      browserSessionId: session.browserSessionId,
      bindingEpoch: view.bindingEpoch!,
      expectedDaemonInstanceId: view.daemonInstanceId!,
    }
    await openIdeAttachment(environmentId, view.viewId, sessionId, resourceId, body)
  }

  function togglePanel(environment: IdeEnvironmentInfo): void {
    if (initial.recoveryError || !isCanonicalUuid(environment.id)) {
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironment(environment.id)
    const current = presentationRef.current
    const viewId = selectedViewForEnvironment(current, environment.id)
    if (viewId == null) {
      if (current.panelVisible && current.selectedEnvironmentId === environment.id) {
        collapsePanel()
        return
      }
      commitPresentation(ensureOpened(current, environment.id, true), 'both')
      const viewId = claimReconciliation.startOrFocus(environment.id, environment, 'files')
      if (viewId != null) {
        persistViewId(environment.id, viewId)
      }
      return
    }
    const nextVisible = current.selectedEnvironmentId === environment.id
      ? !current.panelVisible
      : true
    commitPresentation(ensureOpened(current, environment.id, nextVisible), 'both')
    if (nextVisible) {
      const viewId = claimReconciliation.startOrFocus(environment.id, environment, 'files')
      if (viewId != null) {
        persistViewId(environment.id, viewId)
      }
    }
  }

  function openEnvironment(request: IdeOpenEnvironmentRequest): void {
    if (initial.recoveryError) {
      return
    }
    const environment = request.environment != null && isCanonicalUuid(request.environment.id)
      ? request.environment
      : null
    if (environment == null) {
      setActiveEnvironment(null)
      commitPresentation({ ...presentationRef.current, panelVisible: true, selectedEnvironmentId: null }, 'both')
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironment(environment.id)
    commitPresentation(ensureOpened(presentationRef.current, environment.id, true), 'both')
    const viewId = claimReconciliation.startOrFocus(environment.id, environment, request.mode ?? 'files')
    if (viewId != null) {
      persistViewId(environment.id, viewId)
    }
  }

  function persistViewId(environmentId: string, viewId: string): void {
    const current = presentationRef.current
    const viewIds = current.viewIdsByEnvironment[environmentId] ?? []
    commitPresentation({
      ...current,
      viewIdsByEnvironment: viewIds.includes(viewId)
        ? current.viewIdsByEnvironment
        : { ...current.viewIdsByEnvironment, [environmentId]: [...viewIds, viewId] },
      selectedViewIdsByEnvironment: current.selectedEnvironmentId === environmentId
        ? { ...current.selectedViewIdsByEnvironment, [environmentId]: viewId }
        : current.selectedViewIdsByEnvironment,
    }, 'session')
  }

  function selectEnvironment(environmentId: string): void {
    const environment = environmentMapRef.current.get(environmentId) ?? null
    if (environment == null || !isCanonicalUuid(environment.id)) {
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironment(environment.id)
    const current = presentationRef.current
    const viewId = selectedViewForEnvironment(current, environment.id)
    commitPresentation(ensureOpened(current, environment.id, true), 'both')
    if (viewId != null) {
      claimReconciliation.reconcileView(viewId, 'focus')
    }
  }

  function collapsePanel(): void {
    if (initial.recoveryError) {
      return
    }
    const current = presentationRef.current
    if (!current.panelVisible) {
      return
    }
    commitPresentation({ ...current, panelVisible: false }, 'both')
  }

  useEffect(() => {
    actionsRef.current = { openEnvironment }
  })

  const openEnvironmentStable = useCallback((request: IdeOpenEnvironmentRequest) => {
    actionsRef.current?.openEnvironment(request)
  }, [])

  useEffect(() => {
    if (!environmentsQuery.isSuccess) {
      return
    }
    const available = new Set((environmentsQuery.data ?? []).map((listed) => listed.id))
    const current = presentationRef.current
    const openedEnvironmentIds = current.openedEnvironmentIds.filter(
      (environmentId) => available.has(environmentId),
    )
    if (openedEnvironmentIds.length !== current.openedEnvironmentIds.length) {
      const selectedEnvironmentId = current.selectedEnvironmentId != null
        && available.has(current.selectedEnvironmentId)
        ? current.selectedEnvironmentId
        : openedEnvironmentIds[0] ?? null
      commitPresentation({
        panelVisible: current.panelVisible && openedEnvironmentIds.length > 0,
        selectedEnvironmentId,
        openedEnvironmentIds,
        viewIdsByEnvironment: Object.fromEntries(Object.entries(current.viewIdsByEnvironment)
          .filter(([id]) => available.has(id))),
        selectedViewIdsByEnvironment: Object.fromEntries(Object.entries(current.selectedViewIdsByEnvironment)
          .filter(([id]) => available.has(id))),
      }, 'session')
    }
    setKnownEnvironments((currentKnown) => {
      const filtered: Record<string, IdeEnvironmentInfo> = {}
      let changed = false
      for (const [environmentId, environment] of Object.entries(currentKnown)) {
        if (available.has(environmentId)) {
          filtered[environmentId] = environment
        } else {
          changed = true
        }
      }
      return changed ? filtered : currentKnown
    })
    claimReconciliation.reconcileEnvironments(available)
  }, [environmentsQuery.data, environmentsQuery.isSuccess, claimReconciliation, commitPresentation])

  const selectedEnvironmentId = activeEnvironmentId
  const panelVisible = active && presentation.panelVisible

  const contextValue = {
    openEnvironment: openEnvironmentStable,
    panelVisible,
    togglePanel,
    activateChatEnvironment,
    canOpenAttachment,
    openAttachment,
  }

  return (
    <IdePanelContext.Provider value={contextValue}>
      <div
        className="chat-ide-workspace"
        data-testid="chat-ide-workspace"
        data-active={active ? 'true' : 'false'}
        hidden={!active}
        inert={!active}
      >
        <IdeSplitShell isOpen={panelVisible} panel={<IdeOverlayPanel
          isOpen={panelVisible}
          selectedEnvironmentId={selectedEnvironmentId}
          selectedViewId={selectedEnvironmentId == null
            ? null
            : presentation.selectedViewIdsByEnvironment[selectedEnvironmentId] ?? null}
          openedEnvironmentIds={presentation.openedEnvironmentIds}
          viewsByEnvironment={presentation.viewIdsByEnvironment}
          views={claimReconciliation.views}
          environments={environments}
          sessionStatus={session.status}
          storageUnavailable={storageUnavailable || (!session.sessionSaved && !initial.recoveryError)}
          recoveryError={initial.recoveryError}
          connectionState={connectionState}
          onSelectEnvironment={selectEnvironment}
          onOpenNewView={(environmentId) => {
            if (!initial.recoveryError && presentationRef.current.selectedEnvironmentId === environmentId
              && activeEnvironmentIdRef.current === environmentId && environmentMapRef.current.get(environmentId)?.ready) {
              claimReconciliation.openNewView(environmentId)
            }
          }}
          onSelectView={(viewId) => {
            const view = claimReconciliation.viewsRef.current[viewId]
            if (view == null) {
              return
            }
            const current = presentationRef.current
            setActiveEnvironment(view.environmentId)
            commitPresentation({
              ...current,
              selectedEnvironmentId: view.environmentId,
              selectedViewIdsByEnvironment: {
                ...current.selectedViewIdsByEnvironment,
                [view.environmentId]: viewId,
              },
              panelVisible: true,
            }, 'both')
            claimReconciliation.reconcileView(viewId, 'focus')
          }}
          onCollapse={collapsePanel}
          onCloseEnvironment={closeEnvironment}
          onDismissCloseError={claimReconciliation.dismissCloseError}
          onChangeDirectory={claimReconciliation.changeDirectory}
          onIframeLoaded={claimReconciliation.handleIframeLoaded}
          onRetryClaim={claimReconciliation.retryClaim}
        />}>
          {children}
        </IdeSplitShell>
      </div>
    </IdePanelContext.Provider>
  )

  async function closeEnvironment(viewId: string, discard: boolean): Promise<IdeCloseResult> {
    const view = claimReconciliation.viewsRef.current[viewId]
    if (view == null) {
      return { ok: true, requiresForceConfirmation: false }
    }
    const result = await claimReconciliation.closeEnvironment(viewId, discard)
    if (result.ok) {
      const current = presentationRef.current
      const viewIds = current.viewIdsByEnvironment[view.environmentId] ?? []
      const remaining = viewIds.filter((id) => id !== viewId)
      const viewIdsByEnvironment = { ...current.viewIdsByEnvironment }
      if (remaining.length === 0) {
        delete viewIdsByEnvironment[view.environmentId]
      } else {
        viewIdsByEnvironment[view.environmentId] = remaining
      }
      const selectedViewIdsByEnvironment = { ...current.selectedViewIdsByEnvironment }
      if (selectedViewIdsByEnvironment[view.environmentId] === viewId) {
        if (remaining.length === 0) {
          delete selectedViewIdsByEnvironment[view.environmentId]
        } else {
          const adjacentIndex = Math.min(viewIds.indexOf(viewId), remaining.length - 1)
          selectedViewIdsByEnvironment[view.environmentId] = remaining[adjacentIndex]!
        }
      }
      commitPresentation({
        ...current,
        viewIdsByEnvironment,
        selectedViewIdsByEnvironment,
      }, 'both')
    }
    return result
  }
}
