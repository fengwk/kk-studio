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
import {
  IdePanelContext,
  type IdeActionMode,
  type IdeEnvironmentInfo,
  type IdeOpenEnvironmentRequest,
} from '@/features/environment/ide/ide-panel-context'
import {
  initializeIdeTabState,
  readRecentDirectories,
  recordRecentDirectory,
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
    selectedViewId: null,
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
  const [knownEnvironments, setKnownEnvironments] = useState<Record<string, IdeEnvironmentInfo>>({})
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved && !initial.recoveryError)

  const presentationRef = useRef(presentation)
  const pendingModeRef = useRef<IdeActionMode | null>(null)
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
        selectedViewId: current.selectedEnvironmentId === environmentId ? viewId : current.selectedViewId,
      }, 'session')
    },
  })

  useEffect(() => {
    cloneHandlerRef.current = (browserSessionId) => {
      claimReconciliation.resetForClone()
      const next = clearRuntimePresentation(presentationRef.current)
      presentationRef.current = next
      setPresentationState(next)
      setActiveEnvironmentId(null)
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
    for (const ids of Object.values(viewIdsByEnvironment)) {
      ids.sort((left, right) => {
        const old = current.viewIdsByEnvironment
        const leftRank = Object.values(old).findIndex((values) => values.includes(left))
        const rightRank = Object.values(old).findIndex((values) => values.includes(right))
        return leftRank < 0 && rightRank < 0 ? left.localeCompare(right)
          : leftRank < 0 ? 1 : rightRank < 0 ? -1 : leftRank - rightRank
      })
    }
    const selectedViewId = current.selectedViewId != null
      && claimReconciliation.views[current.selectedViewId] != null
      ? current.selectedViewId
      : current.selectedEnvironmentId == null
        ? null
        : viewIdsByEnvironment[current.selectedEnvironmentId]?.[0] ?? null
    if (
      selectedViewId !== current.selectedViewId
      || JSON.stringify(viewIdsByEnvironment) !== JSON.stringify(current.viewIdsByEnvironment)
    ) {
      commitPresentation({ ...current, viewIdsByEnvironment, selectedViewId }, 'session')
    }
  }, [claimReconciliation.views, commitPresentation])

  useEffect(() => {
    if (!active) {
      setActiveEnvironmentId(null)
    }
  }, [active])

  function ensureOpened(
    current: IdePresentation,
    environmentId: string,
    panelVisible: boolean,
  ): IdePresentation {
    const openedEnvironmentIds = current.openedEnvironmentIds.includes(environmentId)
      ? current.openedEnvironmentIds
      : [...current.openedEnvironmentIds, environmentId]
    const selectedViewId = current.viewIdsByEnvironment[environmentId]?.find(
      (id) => claimReconciliation.viewsRef.current[id] != null,
    ) ?? null
    if (
      current.panelVisible === panelVisible
      && current.selectedEnvironmentId === environmentId
      && openedEnvironmentIds === current.openedEnvironmentIds
    ) {
      return current
    }
    return {
      panelVisible,
      selectedEnvironmentId: environmentId,
      openedEnvironmentIds,
      viewIdsByEnvironment: current.viewIdsByEnvironment,
      selectedViewId,
    }
  }

  function activateChatEnvironment(environment: IdeEnvironmentInfo | null): void {
    if (environment == null || !isCanonicalUuid(environment.id)) {
      setActiveEnvironmentId(null)
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironmentId(environment.id)
    const current = presentationRef.current
    const existingViewId = current.viewIdsByEnvironment[environment.id]?.find(
      (id) => claimReconciliation.viewsRef.current[id] != null,
    )
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

  function togglePanel(environment: IdeEnvironmentInfo): void {
    if (initial.recoveryError || !isCanonicalUuid(environment.id)) {
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironmentId(environment.id)
    const current = presentationRef.current
    const viewId = current.viewIdsByEnvironment[environment.id]?.find(
      (id) => claimReconciliation.viewsRef.current[id] != null,
    )
    if (viewId == null) {
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
      setActiveEnvironmentId(null)
      pendingModeRef.current = request.mode ?? null
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironmentId(environment.id)
    pendingModeRef.current = null
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
      selectedViewId: current.selectedEnvironmentId === environmentId ? viewId : current.selectedViewId,
    }, 'session')
  }

  function selectEnvironment(environmentId: string): void {
    const environment = environmentMapRef.current.get(environmentId) ?? null
    pendingModeRef.current = null
    if (environment == null || !isCanonicalUuid(environment.id)) {
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironmentId(environment.id)
    const current = presentationRef.current
    const viewId = current.viewIdsByEnvironment[environment.id]?.find(
      (id) => claimReconciliation.viewsRef.current[id] != null,
    ) ?? null
    commitPresentation({
      ...current,
      panelVisible: true,
      selectedEnvironmentId: environment.id,
      selectedViewId: viewId,
    }, 'both')
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
        selectedViewId: selectedEnvironmentId == null
          ? null
          : current.viewIdsByEnvironment[selectedEnvironmentId]?.[0] ?? null,
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
  const panelVisible = active
    && presentation.panelVisible
    && (initial.recoveryError || selectedEnvironmentId != null)

  const contextValue = {
    openEnvironment: openEnvironmentStable,
    panelVisible,
    togglePanel,
    activateChatEnvironment,
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
          selectedViewId={presentation.selectedViewId}
          openedEnvironmentIds={presentation.openedEnvironmentIds}
          viewsByEnvironment={presentation.viewIdsByEnvironment}
          views={claimReconciliation.views}
          environments={environments}
          environmentsLoading={environmentsQuery.isLoading}
          environmentsError={environmentsQuery.isError}
          sessionStatus={session.status}
          storageUnavailable={storageUnavailable || (!session.sessionSaved && !initial.recoveryError)}
          recoveryError={initial.recoveryError}
          connectionState={connectionState}
          onSelectEnvironment={selectEnvironment}
          onOpenNewView={(environmentId, directory) => {
            void claimReconciliation.openNewView(environmentId, directory).then((viewId) => {
              if (viewId == null) {
                return
              }
              const view = claimReconciliation.viewsRef.current[viewId]
              if (view == null) {
                return
              }
              const current = presentationRef.current
              const ids = current.viewIdsByEnvironment[environmentId] ?? []
              commitPresentation({
                ...current,
                selectedEnvironmentId: environmentId,
                selectedViewId: viewId,
                viewIdsByEnvironment: ids.includes(viewId)
                  ? current.viewIdsByEnvironment
                  : { ...current.viewIdsByEnvironment, [environmentId]: [...ids, viewId] },
                panelVisible: true,
              }, 'both')
            })
          }}
          onSelectView={(viewId) => {
            const view = claimReconciliation.viewsRef.current[viewId]
            if (view == null) {
              return
            }
            const current = presentationRef.current
            setActiveEnvironmentId(view.environmentId)
            commitPresentation({
              ...current,
              selectedEnvironmentId: view.environmentId,
              selectedViewId: viewId,
              panelVisible: true,
            }, 'both')
            claimReconciliation.reconcileView(viewId, 'focus')
          }}
          onCollapse={collapsePanel}
          onCloseEnvironment={closeEnvironment}
          onDismissCloseError={claimReconciliation.dismissCloseError}
          onChangeDirectory={claimReconciliation.changeDirectory}
          onOpenTerminal={(viewId) => {
            const view = claimReconciliation.viewsRef.current[viewId]
            if (view == null) {
              return
            }
            claimReconciliation.setPendingShell(view.environmentId, viewId)
            claimReconciliation.maybeRunPendingShell(viewId)
          }}
          onIframeLoaded={claimReconciliation.handleIframeLoaded}
          onRetryClaim={claimReconciliation.retryClaim}
          onReloadEnvironments={() => {
            void environmentsQuery.refetch()
          }}
          readRecentDirectories={(environmentId) => readRecentDirectories(environmentId, storage)}
          recordRecentDirectory={(environmentId, directory) =>
            recordRecentDirectory(environmentId, directory, storage)}
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
      const remaining = (current.viewIdsByEnvironment[view.environmentId] ?? [])
        .filter((id) => id !== viewId)
      const viewIdsByEnvironment = { ...current.viewIdsByEnvironment }
      if (remaining.length === 0) {
        delete viewIdsByEnvironment[view.environmentId]
      } else {
        viewIdsByEnvironment[view.environmentId] = remaining
      }
      const selectedViewId = current.selectedViewId === viewId
        ? remaining[0] ?? null
        : current.selectedViewId
      const openedEnvironmentIds = remaining.length === 0
        ? current.openedEnvironmentIds.filter((id) => id !== view.environmentId)
        : current.openedEnvironmentIds
      const selectedEnvironmentId = current.selectedEnvironmentId === view.environmentId
        && remaining.length === 0
        ? openedEnvironmentIds[0] ?? null
        : current.selectedEnvironmentId
      const fallbackViewId = selectedEnvironmentId == null
        ? null
        : viewIdsByEnvironment[selectedEnvironmentId]?.[0] ?? null
      commitPresentation({
        panelVisible: current.panelVisible && openedEnvironmentIds.length > 0,
        selectedEnvironmentId,
        openedEnvironmentIds,
        viewIdsByEnvironment,
        selectedViewId: selectedViewId ?? fallbackViewId,
      }, 'both')
    }
    return result
  }
}
