import { MoveHorizontal, X } from 'lucide-react'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import {
  ModelSelect,
  ParameterControls,
  ParameterJsonToggle,
  PromptEditor,
  RawJsonEditor,
} from '@/features/canvas/generation-panel-config'
import { generationPanelPosition } from '@/features/canvas/generation-panel-position'
import { runDisplayLabel, runStatusKey } from '@/features/canvas/generation-panel-labels'
import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'
import { useFunctionConfigDraft } from '@/features/canvas/useFunctionConfigDraft'
import { useLocalPendingRun, type LocalPendingRun } from '@/features/canvas/useLocalPendingRun'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { useI18n } from '@/shared/i18n'
import type {
  CanvasFunctionDefinitionDTO,
  CanvasFunctionUnknownResolution,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

export interface CanvasGenerationPanelAnchor {
  node: CanvasTransformDTO
  viewport: StoredCanvasViewport
  stage: StageMetrics
}

/**
 * Function 节点的工作台：编辑该节点的函数配置（模型、参数、参考资源、完整 JSON），
 * 展示运行状态，并让用户手动丢弃损坏的本地未决运行记录。
 */
export function CanvasGenerationPanel({
  snapshot,
  node,
  anchor,
}: {
  snapshot: CanvasSnapshot
  node: ResourceNode
  anchor?: CanvasGenerationPanelAnchor
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const { flushFunctionConfig } = runtime

  // 本地未决记录与丢弃确认属于面板生命周期：目录短暂为空导致面板不渲染时也不能重置。
  const [localPending, setLocalPending] = useLocalPendingRun(
    snapshot.document.id,
    node.id,
    runtime.localPendingErrors,
  )
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  // UNKNOWN 运行必须由用户给出核查结论与说明，面板不自动重试。
  const [unknownResolution, setUnknownResolution] = useState<CanvasFunctionUnknownResolution>('RESUME')
  const [verificationText, setVerificationText] = useState('')

  // 离开面板（卸载、切换选中节点）前冲刷尚未发送的函数配置。
  useEffect(() => () => {
    void flushFunctionConfig(node.id)
  }, [flushFunctionConfig, node.id])

  const [expanded, setExpanded] = useState(false)
  const panelRef = useRef<HTMLElement | null>(null)
  const panelSize = useMeasuredPanelSize(panelRef)
  const panelPosition = useMemo(
    () => (anchor ? anchorPlacement(anchor, panelSize) : null),
    [anchor, panelSize],
  )

  const draft = useFunctionConfigDraft({
    snapshot,
    node,
    models: runtime.models,
    scheduleFunctionConfig: runtime.scheduleFunctionConfig,
    onReferenceLimit: () => runtime.setToast(t('canvas.generation.referenceLimit')),
  })

  const { model } = draft
  if (!node.function || !model) {
    return null
  }

  const active = isRunActive(node)
  return (
    <section
      className={`generation-panel ${expanded ? 'expanded' : ''}`}
      aria-label={t('canvas.generation.panelAria')}
      data-placement={panelPosition?.placement}
      ref={panelRef}
      style={anchoredStyle(panelPosition)}
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <PanelHeader
        node={node}
        model={model}
        expanded={expanded}
        onToggleExpanded={() => setExpanded((current) => !current)}
        onClose={() => runtime.setSelection([])}
      />
      {draft.config.rawArgs
        ? <RawJsonEditor draft={draft} />
        : <PromptEditor draft={draft} active={active} />}
      <div className="generation-footer">
        <OutputBadges model={model} />
        <div className="generation-controls">
          <ModelSelect draft={draft} model={model} disabled={active} />
          {draft.config.rawArgs ? null : (
            <>
              <ParameterControls draft={draft} disabled={active} />
              <ParameterJsonToggle draft={draft} />
            </>
          )}
          <UnknownResolutionControls
            node={node}
            resolution={unknownResolution}
            verification={verificationText}
            onResolutionChange={setUnknownResolution}
            onVerificationChange={setVerificationText}
          />
          <LocalPendingRunNotice
            node={node}
            pending={localPending}
            active={active}
            confirmingDiscard={confirmingDiscard}
            onRequestDiscard={() => setConfirmingDiscard(true)}
            onCancelDiscard={() => setConfirmingDiscard(false)}
            onDiscarded={() => {
              setConfirmingDiscard(false)
              setLocalPending(null)
            }}
          />
          <RunFeedback node={node} />
        </div>
      </div>
    </section>
  )
}

/** 面板实测尺寸，用于把它锚定到节点下方；首帧在 layout 阶段测量，之后随 ResizeObserver 更新。 */
function useMeasuredPanelSize(panelRef: React.RefObject<HTMLElement | null>) {
  const [size, setSize] = useState({ width: 560, height: 190 })

  useLayoutEffect(() => {
    const element = panelRef.current
    if (!element) {
      return
    }
    const publish = () => {
      const rect = element.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0) {
        setSize((current) => (
          current.width === rect.width && current.height === rect.height
            ? current
            : { width: rect.width, height: rect.height }
        ))
      }
    }
    publish()
    if (typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(publish)
    observer.observe(element)
    return () => observer.disconnect()
  }, [panelRef])

  return size
}

/** 窄屏（≤1600px）收紧面板与节点的间距，避免小窗口下把面板挤出可视区。 */
function anchorPlacement(
  anchor: CanvasGenerationPanelAnchor,
  panel: { width: number; height: number },
) {
  const compactDesktop = anchor.stage.width <= 1600
  return generationPanelPosition({
    node: anchor.node,
    viewport: anchor.viewport,
    stage: anchor.stage,
    panel,
    gap: compactDesktop ? 8 : undefined,
    padding: compactDesktop ? 4 : undefined,
  })
}

function anchoredStyle(
  position: ReturnType<typeof generationPanelPosition> | null,
): CSSProperties | undefined {
  return position
    ? { bottom: 'auto', left: position.left, top: position.top, transform: 'none' }
    : undefined
}

function isRunActive(node: ResourceNode): boolean {
  return node.run?.status === 'READY' || node.run?.status === 'RUNNING'
}

function PanelHeader({
  node,
  model,
  expanded,
  onToggleExpanded,
  onClose,
}: {
  node: ResourceNode
  model: CanvasFunctionDefinitionDTO
  expanded: boolean
  onToggleExpanded: () => void
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <div className="generation-panel-head">
      <div>
        <span className="generation-node-kicker">{t('canvas.generation.nodeKicker')}</span>
        <strong>{model.description || model.name}</strong>
        <span className={`generation-node-status ${node.run?.status.toLowerCase() ?? 'ready'}`}>
          {node.run ? t(runStatusKey(node.run.status)) : t('canvas.generation.status.ready')}
        </span>
      </div>
      <div className="generation-panel-actions">
        <IconButton
          label={t(expanded ? 'canvas.generation.collapse' : 'canvas.generation.expand')}
          size="compact"
          aria-expanded={expanded}
          onClick={onToggleExpanded}
        >
          <MoveHorizontal aria-hidden="true" />
        </IconButton>
        <IconButton
          className="close-panel"
          label={t('canvas.generation.close')}
          size="compact"
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </IconButton>
      </div>
    </div>
  )
}

function OutputBadges({ model }: { model: CanvasFunctionDefinitionDTO }) {
  const { t } = useI18n()
  if (!model.outputs || model.outputs.length === 0) {
    return null
  }
  return (
    <div className="generation-outputs-badge-list" aria-label={t('canvas.generation.outputsAria')}>
      {model.outputs.map((output, index) => (
        <span
          key={index}
          className="generation-output-badge"
          title={output.name ? `${output.name} · ${output.kind}` : output.kind}
        >
          {output.kind}{output.name ? `: ${output.name}` : ''}
        </span>
      ))}
    </div>
  )
}

/** UNKNOWN 运行必须由用户给出核查结论与说明，面板不自动重试。 */
function UnknownResolutionControls({
  node,
  resolution,
  verification,
  onResolutionChange,
  onVerificationChange,
}: {
  node: ResourceNode
  resolution: CanvasFunctionUnknownResolution
  verification: string
  onResolutionChange: (next: CanvasFunctionUnknownResolution) => void
  onVerificationChange: (next: string) => void
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  if (node.run?.status !== 'UNKNOWN') {
    return null
  }
  const note = verification.trim()
  const options: SelectOption[] = [
    { value: 'RESUME', label: t('canvas.generation.resolution.resume') },
    { value: 'FAILED', label: t('canvas.generation.resolution.failed') },
    { value: 'CANCELLED', label: t('canvas.generation.resolution.cancelled') },
  ]
  return (
    <div
      className="generation-unknown-resolution"
      role="region"
      aria-label={t('canvas.generation.unknownRegionAria')}
    >
      <div className="generation-unknown-title">{t('canvas.generation.unknownTitle')}</div>
      <div className="generation-unknown-controls">
        <Select
          aria-label={t('canvas.generation.verifyDecision')}
          value={resolution}
          options={options}
          onChange={(next) => onResolutionChange(next as CanvasFunctionUnknownResolution)}
        />
        <TextInput
          className="generation-verify-note"
          aria-label={t('canvas.generation.verifyNote')}
          placeholder={t('canvas.generation.verifyNotePlaceholder')}
          value={verification}
          onChange={(event) => onVerificationChange(event.target.value)}
        />
        <Button
          size="compact"
          disabled={!note}
          onClick={() => {
            if (node.run?.requestId && note) {
              void runtime.resolveFunctionRun(node.id, node.run.requestId, resolution, note)
            }
          }}
        >
          {t('canvas.generation.verifySubmit')}
        </Button>
      </div>
    </div>
  )
}

/** 本地未决运行记录的告警与手动丢弃入口：损坏记录可读，但只在用户确认后清理。 */
function LocalPendingRunNotice({
  node,
  pending,
  active,
  confirmingDiscard,
  onRequestDiscard,
  onCancelDiscard,
  onDiscarded,
}: {
  node: ResourceNode
  pending: LocalPendingRun | null
  active: boolean
  confirmingDiscard: boolean
  onRequestDiscard: () => void
  onCancelDiscard: () => void
  onDiscarded: () => void
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  if (!pending) {
    return null
  }
  return (
    <>
      <div className="generation-local-pending-error" role="alert" data-testid="local-pending-error">
        <strong>{t('canvas.generation.localPendingTitle')}</strong>
        <p>{pending.message}</p>
        <Button
          variant="inline"
          size="compact"
          danger
          data-testid="discard-pending-btn"
          disabled={active || Boolean(runtime.isNodeInFlight?.(node.id))}
          onClick={onRequestDiscard}
        >
          {t('canvas.generation.discardPending')}
        </Button>
      </div>
      <ConfirmActionModal
        className="modal-touch-target"
        modal={confirmingDiscard ? {
          title: t('canvas.generation.discardConfirmAria'),
          description: t('canvas.generation.discardConfirmText'),
          confirmLabel: t('canvas.generation.discardConfirm'),
          icon: 'delete',
          tone: 'danger',
          onConfirm: () => {
            if (pending.raw !== null && runtime.discardPendingRun(node.id, pending.raw)) {
              onDiscarded()
            }
          },
        } : null}
        pending={false}
        onClose={onCancelDiscard}
      />
    </>
  )
}

function RunFeedback({ node }: { node: ResourceNode }) {
  const { t } = useI18n()
  if (node.run?.status === 'FAILED') {
    return (
      <span className="generation-run-feedback failed" role="alert">
        {node.run.error ?? t('canvas.generation.runFailed')}
      </span>
    )
  }
  if (!node.run) {
    return null
  }
  return (
    <span className={`generation-run-feedback ${node.run.status.toLowerCase()}`}>
      {runDisplayLabel(t, node.run.status, node.run.stage)}
    </span>
  )
}
