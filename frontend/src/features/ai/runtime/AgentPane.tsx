import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { paneTargetViewKey, type ThreadPresentation } from './thread-presentation'
import { ThreadPresentationActions } from './ThreadPresentationActions'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
import {
  BoundThreadView,
} from '@/features/ai/runtime/ChildThreadView'
import { RootAgentPane } from '@/features/ai/runtime/RootAgentPane'
import { ThreadNavigationContext } from '@/features/ai/runtime/thread-navigation-context'
import {
  useThreadNavigation,
} from '@/features/ai/runtime/useThreadNavigation'
import { usePaneTarget } from '@/features/ai/runtime/usePaneTarget'
import { useThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import { isBoundTarget, samePaneTarget } from '@/features/ai/runtime/agent-pane'
import type {
  AgentPaneCapabilities,
  AgentPaneDefaults,
  BranchRequestInput,
  PaneReport,
} from '@/features/ai/runtime/useRootThreadControl'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'
import '@/features/ai/runtime/child-thread-view.css'

export type { AgentPaneCapabilities, AgentPaneDefaults, BranchRequestInput, PaneReport }

/**
 * Pane 父面板：只管理绑定目标、Thread 身份分流与 pane 内查看路径。
 *
 * - 只读投影在这里创建一次并向下传递（子层与根控面板不重复订阅）；
 * - 目标未绑定 Thread（草稿）或身份已确认为执行根时，才渲染 `RootAgentPane`
 *   （唯一挂载草稿、上传与人工执行 Hook 的地方）；
 * - 子代理目标与身份未确认时只渲染只读视图，根控制 Hook 不会出现；
 * - 查看子代理期间根层保持挂载（隐藏为 inert），草稿、上传与滚动位置原地保留。
 */
export function AgentPane({
  owner,
  paneId,
  agents,
  environments = [],
  defaults = {},
  focused = false,
  hidden = false,
  onValidateDraftName,
  onFocus,
  initialTarget,
  onTargetConsumed,
  capabilities,
  onRequestBranch,
  onReport,
  onPresentation,
}: {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  defaults?: AgentPaneDefaults
  focused?: boolean
  hidden?: boolean
  onValidateDraftName?: (target: PaneTarget, name: string) => Promise<string | null>
  onFocus?: () => void
  initialTarget?: PaneTarget
  onTargetConsumed?: (target: PaneTarget) => void
  capabilities?: AgentPaneCapabilities
  /** 新建分支入口；由 Chat workspace 提供（命名与目标 pane 在 workspace 统一决定）。 */
  onRequestBranch?: (request: BranchRequestInput) => void
  /** 面板运行时摘要上报；只读路径由本组件按投影派生。 */
  onReport?: (report: PaneReport) => void
  onPresentation?: (report: ThreadPresentation | null) => void
}) {
  const paneSectionRef = useRef<HTMLElement | null>(null)
  const { t } = useI18n()
  const { target, targetRef, setTarget } = usePaneTarget({ owner, paneId, initialTarget })
  const [focusTarget, setFocusTarget] = useState<PaneTarget | null>(initialTarget ?? null)
  const lastRoutedTarget = useRef(initialTarget)
  useEffect(() => {
    if (initialTarget != null && initialTarget !== lastRoutedTarget.current) {
      setFocusTarget(initialTarget)
    }
    lastRoutedTarget.current = initialTarget
  }, [initialTarget])
  useEffect(() => {
    if (!focused) {
      setFocusTarget(null)
    }
  }, [focused])
  const boundThreadId = isBoundTarget(target) ? target.threadId : ''
  // 唯一的只读投影：身份判定、只读子视图与根控制面共用同一份订阅。
  const projection = useThreadProjection(boundThreadId)
  // 身份未加载完成前为 null：此时既不暴露控制区，也不允许 pane 内导航。
  const isRoot = boundThreadId === '' ? null
    : projection.thread?.threadId === boundThreadId
      ? projection.thread.parentThreadId == null
      : null
  // 只有草稿目标或已确认为执行根的绑定才允许挂载根控制面：身份未确认的绑定可能
  // 是子代理，绝不提前挂载草稿、上传与人工执行 Hook（也不给它们造上传注册表）。
  const needsControl = !capabilities?.readOnly && (!isBoundTarget(target) || isRoot === true)
  const navigation = useThreadNavigation({
    rootThreadId: boundThreadId === '' ? null : boundThreadId,
    enabled: isRoot === true,
    fallbackFocusRef: paneSectionRef,
  })

  // initialTarget 消费：控制面挂载时由它按 pending 门禁消费并给出错误；
  // 只读路径（子代理目标/身份未确认）没有控制面，这里直接消费。
  useEffect(() => {
    if (!initialTarget || needsControl) {
      return
    }
    if (samePaneTarget(targetRef.current, initialTarget)) {
      onTargetConsumed?.(initialTarget)
      return
    }
    setTarget(initialTarget)
    onTargetConsumed?.(initialTarget)
  }, [initialTarget, needsControl, onTargetConsumed, setTarget, targetRef])

  // 只读路径没有控制面：由本组件按投影派生摘要，供 workspace 的路由与面包屑使用。
  const readOnlyReportRef = useRef(onReport)
  useEffect(() => {
    readOnlyReportRef.current = onReport
  })
  const readOnlySessionId = projection.thread?.sessionId ?? null
  const readOnlyBranchName = projection.thread?.threadId === boundThreadId
    ? projection.thread.name
    : null
  useEffect(() => {
    if (needsControl) {
      return
    }
    readOnlyReportRef.current?.({
      target,
      draftKey: '',
      pending: false,
      hasUnsentDraft: false,
      sessionId: readOnlySessionId,
      branchName: readOnlyBranchName,
    })
  }, [needsControl, readOnlyBranchName, readOnlySessionId, target])

  const covered = hidden || navigation.layers.length > 0
  const topLayerIndex = navigation.layers.length - 1
  const expectedViewKey = navigation.activeThreadId
    ? `thread:${navigation.activeThreadId}` : paneTargetViewKey(target)
  const expectedKeyRef = useRef(expectedViewKey)
  useLayoutEffect(() => { expectedKeyRef.current = expectedViewKey })
  const [presentation, setPresentation] = useState<ThreadPresentation | null>(null)
  const acceptPresentation = useCallback((report: ThreadPresentation) => {
    if (report.viewKey === expectedKeyRef.current) {
      setPresentation(report)
    }
  }, [])
  const visiblePresentation = presentation?.viewKey === expectedViewKey ? presentation : null
  useLayoutEffect(() => {
    if (onPresentation == null && visiblePresentation?.mode === 'conversation') {
      visiblePresentation.act(visiblePresentation.viewKey, 'restore-focus')
    }
  }, [onPresentation, visiblePresentation])
  const presentationCallback = useRef(onPresentation)
  useLayoutEffect(() => { presentationCallback.current = onPresentation })
  useEffect(() => {
    presentationCallback.current?.(visiblePresentation)
  }, [visiblePresentation])
  const openParent = isRoot === true ? navigation.openThread : undefined

  // 只有根面板能接管 pane 内导航；只读路径不提供接管者（ThreadLink 保留独立地址）。
  return (
    <ThreadNavigationContext.Provider value={isRoot === true ? navigation.openThread : null}>
      <section
        ref={paneSectionRef}
        tabIndex={-1}
        className={`chat-pane ${focused ? 'focused' : ''}`}
        data-pane-id={paneId}
        hidden={hidden}
        inert={hidden}
        onMouseDown={onFocus}
        onKeyDown={(event) => {
          if (visiblePresentation?.mode === 'debug' && event.key === 'Escape'
            && !event.defaultPrevented && !event.repeat && !event.nativeEvent.isComposing && event.keyCode !== 229
            && !shouldDeferToBlockingModal(event.currentTarget)) {
            event.preventDefault()
            event.stopPropagation()
            visiblePresentation.act(visiblePresentation.viewKey, 'close-debug')
          }
        }}
      >
        {onPresentation == null && (!needsControl || visiblePresentation?.parentThreadId || visiblePresentation?.mode === 'debug') ? <header className="agent-pane-thread-heading">
          {visiblePresentation?.mode === 'debug' ? <Button variant="ghost"
            onClick={() => visiblePresentation.act(visiblePresentation.viewKey, 'close-debug')}>
            {t('ai.runtime.debug.close')}
          </Button> : <ThreadPresentationActions view={visiblePresentation} />}
          {visiblePresentation?.mode !== 'debug' ? <>
            {visiblePresentation?.name ? <h2 className="agent-pane-thread-title" title={visiblePresentation.name}>{visiblePresentation.name}</h2> : null}
            <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
          </> : null}
        </header> : null}
        {/* 被覆盖的根层保持挂载但完全惰性：不可聚焦、不可点、不参与无障碍树。 */}
        <div className="chat-pane-layer" hidden={covered} inert={covered}>
          {needsControl ? (
            <RootAgentPane
              owner={owner}
              paneId={paneId}
              agents={agents}
              environments={environments}
              defaults={defaults}
              focused={focused}
              initialTarget={initialTarget}
              onTargetConsumed={onTargetConsumed}
              capabilities={capabilities}
              onRequestBranch={onRequestBranch}
              onReport={onReport}
              onValidateDraftName={onValidateDraftName}
              target={target}
              setTarget={setTarget}
              projection={projection}
              navigation={navigation}
              covered={covered}
              focusTarget={focusTarget}
              onFocusTargetChange={setFocusTarget}
              presentationSelected={navigation.layers.length === 0}
              onPresentation={acceptPresentation}
            />
          ) : (
            <BoundThreadView
              threadId={boundThreadId}
              projection={projection}
              environments={environments}
              readOnly
              selected={navigation.layers.length === 0}
              active={!hidden}
              onPresentation={acceptPresentation}
              onParent={openParent}
            />
          )}
        </div>
        {navigation.layers.map((layer, index) => (
          <div
            key={layer.threadId}
            className="chat-pane-layer"
            hidden={hidden || index !== topLayerIndex}
            inert={hidden || index !== topLayerIndex}
          >
            <BoundThreadView
              threadId={layer.threadId}
              environments={environments}
              readOnly
              selected={index === topLayerIndex}
              active={!hidden && index === topLayerIndex}
              onPresentation={acceptPresentation}
              onParent={openParent}
            />
          </div>
        ))}
      </section>
    </ThreadNavigationContext.Provider>
  )
}
