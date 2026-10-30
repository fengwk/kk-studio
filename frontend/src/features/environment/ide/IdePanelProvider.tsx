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
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/generated/contracts'
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
  saveIdePresentation,
  type IdePresentation,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'
import { IdeOverlayPanel, type IdeCloseResult } from '@/features/environment/ide/IdeOverlayPanel'
import {
  recoveryFromViews,
  type IdeViewState,
  type IdeViewStatus,
} from '@/features/environment/ide/ide-claim-reducer'
import { useIdeClaimReconciliation } from '@/features/environment/ide/use-ide-claim-reconciliation'

export type { IdeViewState, IdeViewStatus, IdeActionMode }

export interface IdePanelStorageProps {
  storage?: IdeTabStorage
}

function toEnvironmentInfo(card: EnvironmentCardDTO): IdeEnvironmentInfo {
  return { id: card.id, name: card.name, ready: card.ready }
}

/**
 * tab 全局 IDE 面板 Provider：组合 Context 与视图。
 * 内存是唯一事实源，session 保存恢复身份，local 只保存最后展示意图。
 * 内部职责拆分为：
 * 1. 纯 claim reducer（ide-claim-reducer.ts）：状态、尝试序号、飞行动作与身份跃迁；
 * 2. 存储适配（ide-tab-state.ts）：唯一 sessionStorage/localStorage 适配，无额外缓存；
 * 3. 连接身份 claim 对账（use-ide-claim-reconciliation.ts）：WS 身份订阅、对账、epoch 围栏；
 * 4. Provider 本身（IdePanelProvider.tsx）：组合 Context 与视图。
 */
export function IdePanelProvider({
  children,
  storage,
}: PropsWithChildren<IdePanelStorageProps>) {
  const queryClient = useQueryClient()
  const [initial] = useState(() => initializeIdeTabState(storage))
  const [presentation, setPresentationState] = useState<IdePresentation>(initial.presentation)
  const [knownEnvironments, setKnownEnvironments] = useState<Record<string, IdeEnvironmentInfo>>({})
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved)

  const presentationRef = useRef(presentation)
  const pendingModeRef = useRef<IdeActionMode | null>(null)
  const actionsRef = useRef<{
    openEnvironment: (request: IdeOpenEnvironmentRequest) => void
  } | null>(null)

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: presentation.panelVisible || presentation.openedEnvironmentIds.length > 0,
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
    for (const card of environmentsQuery.data ?? []) {
      map.set(card.id, toEnvironmentInfo(card))
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

  const claimReconciliation = useIdeClaimReconciliation({
    storage,
    initialPresentation: initial.presentation,
    initialRecovery: initial.recoveryByEnvironment,
    getPresentation,
    getEnvironmentMap,
    onStorageStatusChange: handleStorageStatusChange,
  })

  const commitPresentation = useCallback((next: IdePresentation, write: 'both' | 'session' | 'none' = 'both') => {
    presentationRef.current = next
    setPresentationState(next)
    if (write === 'both') {
      const saved = saveIdePresentation(
        next,
        recoveryFromViews(claimReconciliation.viewsRef.current, next.openedEnvironmentIds),
        storage,
      )
      setStorageUnavailable(!saved.sessionSaved)
    } else if (write === 'session') {
      claimReconciliation.writeSession()
    }
  }, [claimReconciliation, storage])

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

  function applyOpenRequest(
    request: IdeOpenEnvironmentRequest,
    environment: IdeEnvironmentInfo | null,
  ): void {
    const environmentId = environment?.id ?? null
    if (environment != null) {
      rememberEnvironment(environment)
    }
    const current = presentationRef.current
    const openedEnvironmentIds = environmentId == null
      ? current.openedEnvironmentIds
      : current.openedEnvironmentIds.includes(environmentId)
        ? current.openedEnvironmentIds
        : [...current.openedEnvironmentIds, environmentId]
    const changed = (
      current.panelVisible !== true
      || current.selectedEnvironmentId !== environmentId
      || openedEnvironmentIds !== current.openedEnvironmentIds
    )
    if (changed) {
      commitPresentation({
        panelVisible: true,
        selectedEnvironmentId: environmentId,
        openedEnvironmentIds,
      }, 'both')
    }
    if (environmentId == null) {
      pendingModeRef.current = request.mode ?? null
      return
    }
    pendingModeRef.current = null
    claimReconciliation.startOrFocus(environmentId, environment, request.mode ?? 'files')
  }

  function openEnvironment(request: IdeOpenEnvironmentRequest): void {
    const environment = request.environment != null && isCanonicalUuid(request.environment.id)
      ? request.environment
      : null
    applyOpenRequest(request, environment)
  }

  function selectEnvironment(environmentId: string): void {
    const environment = environmentMapRef.current.get(environmentId) ?? null
    const mode = pendingModeRef.current ?? 'files'
    pendingModeRef.current = null
    applyOpenRequest({ environment, mode }, environment)
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
    const available = new Set((environmentsQuery.data ?? []).map((card) => card.id))
    const current = presentationRef.current
    const openedEnvironmentIds = current.openedEnvironmentIds.filter(
      (environmentId) => available.has(environmentId),
    )
    const removed = openedEnvironmentIds.length !== current.openedEnvironmentIds.length
    if (removed) {
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

  const contextValue = useMemo(
    () => ({ openEnvironment: openEnvironmentStable }),
    [openEnvironmentStable],
  )

  return (
    <IdePanelContext.Provider value={contextValue}>
      {children}
      <IdeOverlayPanel
        isOpen={presentation.panelVisible}
        selectedEnvironmentId={presentation.selectedEnvironmentId}
        openedEnvironmentIds={presentation.openedEnvironmentIds}
        views={claimReconciliation.views}
        environments={environments}
        environmentsLoading={environmentsQuery.isLoading}
        environmentsError={environmentsQuery.isError}
        identityReady={claimReconciliation.identity != null}
        storageUnavailable={storageUnavailable}
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
      />
    </IdePanelContext.Provider>
  )
}
