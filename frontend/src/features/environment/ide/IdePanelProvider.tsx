import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
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
import {
  recoveryFromViews,
  type IdeViewState,
  type IdeViewStatus,
} from '@/features/environment/ide/ide-view-state'
import { useIdeClaimReconciliation } from '@/features/environment/ide/use-ide-claim-reconciliation'
import { useIdeBrowserSession } from '@/features/environment/ide/ide-browser-session'

export type { IdeViewState, IdeViewStatus, IdeActionMode }

export interface IdePanelStorageProps {
  storage?: IdeTabStorage
}

export interface IdePanelProviderProps extends IdePanelStorageProps {
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
  const [initial] = useState(() => initializeIdeTabState(storage))
  const [presentation, setPresentationState] = useState<IdePresentation>(initial.presentation)
  const [activeEnvironmentId, setActiveEnvironmentId] = useState<string | null>(null)
  const [knownEnvironments, setKnownEnvironments] = useState<Record<string, IdeEnvironmentInfo>>({})
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved)

  const presentationRef = useRef(presentation)
  const pendingModeRef = useRef<IdeActionMode | null>(null)
  const actionsRef = useRef<{
    openEnvironment: (request: IdeOpenEnvironmentRequest) => void
  } | null>(null)
  const cloneHandlerRef = useRef<() => void>(() => {})
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
    onClone: () => cloneHandlerRef.current(),
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
  })

  cloneHandlerRef.current = () => {
    claimReconciliation.resetForClone()
    const next = clearRuntimePresentation(presentationRef.current)
    presentationRef.current = next
    setPresentationState(next)
    setActiveEnvironmentId(null)
    const saved = saveIdePresentation(
      session.browserSessionId,
      next,
      {},
      storage,
    )
    setStorageUnavailable(!saved.sessionSaved)
  }
  restoreHandlerRef.current = () => {
    claimReconciliation.restoreUsage()
    claimReconciliation.reconcileAll()
  }
  suspendHandlerRef.current = () => {
    claimReconciliation.withdrawUsage()
  }

  const commitPresentation = useCallback((
    next: IdePresentation,
    write: 'both' | 'session' | 'none' = 'both',
  ) => {
    presentationRef.current = next
    setPresentationState(next)
    if (write === 'both') {
      const saved = saveIdePresentation(
        session.browserSessionId,
        next,
        recoveryFromViews(claimReconciliation.viewsRef.current, next.openedEnvironmentIds),
        storage,
      )
      setStorageUnavailable(!saved.sessionSaved)
    } else if (write === 'session') {
      claimReconciliation.writeSession()
    }
  }, [claimReconciliation, session.browserSessionId, storage])

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
    const view = claimReconciliation.viewsRef.current[environment.id]
    if (view == null) {
      // 切换 Chat 时没有已打开 view 不自动创建；只更新选择偏好。
      commitPresentation({
        ...current,
        selectedEnvironmentId: environment.id,
      }, 'session')
      return
    }
    commitPresentation(ensureOpened(current, environment.id, true), 'both')
    claimReconciliation.startOrFocus(environment.id, environment, 'files')
  }

  function togglePanel(environment: IdeEnvironmentInfo): void {
    if (!isCanonicalUuid(environment.id)) {
      return
    }
    rememberEnvironment(environment)
    setActiveEnvironmentId(environment.id)
    const current = presentationRef.current
    const view = claimReconciliation.viewsRef.current[environment.id]
    if (view == null) {
      commitPresentation(ensureOpened(current, environment.id, true), 'both')
      claimReconciliation.startOrFocus(environment.id, environment, 'files')
      return
    }
    const nextVisible = current.selectedEnvironmentId === environment.id
      ? !current.panelVisible
      : true
    commitPresentation(ensureOpened(current, environment.id, nextVisible), 'both')
    if (nextVisible) {
      claimReconciliation.startOrFocus(environment.id, environment, 'files')
    }
  }

  function openEnvironment(request: IdeOpenEnvironmentRequest): void {
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
    claimReconciliation.startOrFocus(environment.id, environment, request.mode ?? 'files')
  }

  function selectEnvironment(environmentId: string): void {
    const environment = environmentMapRef.current.get(environmentId) ?? null
    const mode = pendingModeRef.current ?? 'files'
    pendingModeRef.current = null
    if (environment != null) {
      openEnvironment({ environment, mode })
    }
  }

  function collapsePanel(): void {
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

  const selectedEnvironmentId = activeEnvironmentId ?? presentation.selectedEnvironmentId
  const selectedView = selectedEnvironmentId == null
    ? undefined
    : claimReconciliation.views[selectedEnvironmentId]
  const panelVisible = active
    && presentation.panelVisible
    && selectedEnvironmentId != null
    && selectedView != null

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
          openedEnvironmentIds={presentation.openedEnvironmentIds}
          views={claimReconciliation.views}
          environments={environments}
          environmentsLoading={environmentsQuery.isLoading}
          environmentsError={environmentsQuery.isError}
          identityReady={claimReconciliation.identity != null
            && (session.status === 'ready' || session.status === 'cloned')}
          sessionStatus={session.status}
          storageUnavailable={storageUnavailable || !session.sessionSaved}
          onSelectEnvironment={selectEnvironment}
          onCollapse={collapsePanel}
          onCloseEnvironment={closeEnvironment}
          onDismissCloseError={claimReconciliation.dismissCloseError}
          onChangeDirectory={claimReconciliation.changeDirectory}
          onOpenTerminal={(environmentId) => {
            claimReconciliation.setPendingShell(environmentId)
            claimReconciliation.maybeRunPendingShell(environmentId)
          }}
          onIframeLoaded={claimReconciliation.handleIframeLoaded}
          onOpenNewView={claimReconciliation.openNewView}
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

  async function closeEnvironment(environmentId: string, discard: boolean): Promise<IdeCloseResult> {
    const result = await claimReconciliation.closeEnvironment(environmentId, discard)
    if (result.ok) {
      const current = presentationRef.current
      const openedEnvironmentIds = current.openedEnvironmentIds.filter(
        (id) => id !== environmentId,
      )
      const selectedEnvironmentId = current.selectedEnvironmentId === environmentId
        ? openedEnvironmentIds[0] ?? null
        : current.selectedEnvironmentId
      commitPresentation({
        panelVisible: current.panelVisible && openedEnvironmentIds.length > 0,
        selectedEnvironmentId,
        openedEnvironmentIds,
      }, 'both')
    }
    return result
  }
}
