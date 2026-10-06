import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import { generationPanelPosition } from '@/features/canvas/generation-panel-position'
import {
  canAddReference,
  createDefaultFunctionConfig,
  extractParametersFromDefinition,
  filterConfigReferences,
  parseFunctionConfig,
  referenceCandidates,
  referenceKey,
  type ReferenceCandidate,
} from '@/features/canvas/generation'
import { CanvasResourceThumbnail } from '@/features/canvas/nodes/resources/CanvasResourceThumbnail'
import type { CanvasFunctionConfig, StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'
import { inspectLocalPendingRun } from '@/features/canvas/function-run'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { useI18n } from '@/shared/i18n'
import type {
  CanvasFunctionDefinitionDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const UNKNOWN_RESOLUTION_OPTIONS: SelectOption[] = [
  { value: 'RESUME', label: 'RESUME (继续运行)' },
  { value: 'FAILED', label: 'FAILED (标记失败)' },
  { value: 'CANCELLED', label: 'CANCELLED (取消运行)' },
]

export interface CanvasGenerationPanelAnchor {
  node: CanvasTransformDTO
  viewport: StoredCanvasViewport
  stage: StageMetrics
}

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
  const sourceModel = modelForNode(runtime.models, node)
  const [modelKey, setModelKey] = useState(sourceModel?.name ?? node.function?.name ?? '')
  const model = runtime.models.find((item) => item.name === modelKey) ?? sourceModel
  const [expanded, setExpanded] = useState(false)
  const [config, setConfig] = useState<CanvasFunctionConfig>(() => (
    sourceModel && node.function
      ? parseFunctionConfig(node.function.args, sourceModel)
      : sourceModel
        ? createDefaultFunctionConfig(sourceModel)
        : { prompt: '', references: [], parameters: {} }
  ))
  const [unknownResolution, setUnknownResolution] = useState<'RESUME' | 'FAILED' | 'CANCELLED'>('RESUME')
  const [verificationText, setVerificationText] = useState('')
  const [showJsonArgs, setShowJsonArgs] = useState(false)
  const [jsonArgsText, setJsonArgsText] = useState(() => JSON.stringify(config.rawArgs ?? config.parameters, null, 2))
  const [jsonDirty, setJsonDirty] = useState(false)
  const [jsonError, setJsonError] = useState<string | null>(null)
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const [localPendingState, setLocalPendingState] = useState<{ message: string; raw: string | null } | null>(null)

  useEffect(() => {
    if (runtime.localPendingErrors?.[node.id]) {
      setLocalPendingState(runtime.localPendingErrors[node.id])
      return
    }
    const canvasId = snapshot.document.id
    if (canvasId) {
      const inspected = inspectLocalPendingRun(canvasId, node.id)
      if (inspected.error && inspected.raw !== null) {
        setLocalPendingState({ message: inspected.error, raw: inspected.raw })
      } else {
        setLocalPendingState(null)
      }
    }
  }, [node.id, snapshot.document.id, runtime.localPendingErrors])

  const [panelSize, setPanelSize] = useState({ width: 560, height: 190 })
  const panelRef = useRef<HTMLElement | null>(null)
  const promptInputRef = useRef<HTMLTextAreaElement | null>(null)

  // Every local edit gets a monotonic version and semantic source identity.
  const draftVersionRef = useRef(0)
  const dirtyRef = useRef(false)
  const sourceRef = useRef<{ identity: string; modelSignature: string } | null>(null)
  const localSourceVersionsRef = useRef(new Map<string, number>())

  const candidates = useMemo(
    () => model ? referenceCandidates(snapshot, node.id, model) : [],
    [model, node.id, snapshot],
  )
  const candidateByKey = useMemo(
    () => new Map(candidates.map((candidate) => [
      referenceKey(candidate.nodeId, candidate.index),
      candidate,
    ])),
    [candidates],
  )
  const referencedKeys = useMemo(
    () => new Set(config.references.map((ref) => referenceKey(ref.nodeId, ref.index))),
    [config.references],
  )

  const panelPosition = useMemo(() => (
    anchor
      ? (() => {
        const compactDesktop = anchor.stage.width <= 1600
        return generationPanelPosition({
          node: anchor.node,
          viewport: anchor.viewport,
          stage: anchor.stage,
          panel: panelSize,
          gap: compactDesktop ? 8 : undefined,
          padding: compactDesktop ? 4 : undefined,
        })
      })()
      : null
  ), [anchor, panelSize])

  const panelStyle: CSSProperties | undefined = panelPosition ? {
    bottom: 'auto',
    left: panelPosition.left,
    top: panelPosition.top,
    transform: 'none',
  } : undefined

  useEffect(() => () => {
    void flushFunctionConfig(node.id)
  }, [flushFunctionConfig, node.id])

  useLayoutEffect(() => {
    const element = panelRef.current
    if (!element) {
      return
    }
    const publish = () => {
      const rect = element.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0) {
        setPanelSize((current) => (
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
  }, [])

  const sourceConfig = useMemo(() => (
    node.function && sourceModel
      ? parseFunctionConfig(node.function.args, sourceModel)
      : null
  ), [node.function, sourceModel])

  const sourceIdentity = node.function && sourceConfig
    ? functionSourceIdentity(node.function.name, sourceConfig)
    : ''
  const sourceModelSignature = sourceModel ? JSON.stringify(sourceModel) : ''

  useEffect(() => {
    if (!node.function || !sourceModel || !sourceConfig) {
      return
    }
    const previous = sourceRef.current
    if (
      previous?.identity === sourceIdentity
      && previous.modelSignature === sourceModelSignature
    ) {
      return
    }
    sourceRef.current = {
      identity: sourceIdentity,
      modelSignature: sourceModelSignature,
    }
    const acknowledgedVersion = localSourceVersionsRef.current.get(sourceIdentity)
    if (acknowledgedVersion !== undefined) {
      for (const [identity, version] of localSourceVersionsRef.current) {
        if (version <= acknowledgedVersion) {
          localSourceVersionsRef.current.delete(identity)
        }
      }
      if (dirtyRef.current && draftVersionRef.current > acknowledgedVersion) {
        return
      }
    } else {
      localSourceVersionsRef.current.clear()
    }
    setModelKey(node.function.name)
    if (dirtyRef.current) {
      // 保留正在键入的 prompt，同步更新外部 references/parameters 变更
      setConfig((current) => ({
        ...current,
        rawArgs: current.rawArgs ? (jsonDirty ? current.rawArgs : sourceConfig.rawArgs) : undefined,
        rawError: sourceConfig.rawError,
        references: sourceConfig.references,
        parameters: jsonDirty ? current.parameters : sourceConfig.parameters,
      }))
    } else {
      setConfig(sourceConfig)
      dirtyRef.current = false
      if (!jsonDirty) {
        setJsonArgsText(JSON.stringify(sourceConfig.rawArgs ?? sourceConfig.parameters, null, 2))
        setJsonError(null)
      }
    }
  }, [
    jsonDirty,
    node.function,
    sourceIdentity,
    sourceConfig,
    sourceModel,
    sourceModelSignature,
  ])

  useEffect(() => {
    if (!model) {
      return
    }
    const filtered = filterConfigReferences(config, candidates, model)
    if (JSON.stringify(filtered.references) !== JSON.stringify(config.references)) {
      updateConfig(filtered)
    }
    // Candidate changes are the only reason for this reconciliation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candidates, model])

  if (!node.function || !model) {
    return null
  }

  const activeModel = model
  const outputKind = sourceModel?.outputs?.[0]?.kind
    ?? activeModel.outputs?.[0]?.kind
  const modelOptions = runtime.models.filter((item) =>
    item.outputs?.[0]?.kind === outputKind
  )
  const modelSelectOptions: SelectOption[] = modelOptions.map((item) => ({
    value: item.name,
    label: item.available === false
      ? t('canvas.generation.modelUnavailableOption', {
        label: item.description || item.name,
        reason: item.unavailableReason ?? t('canvas.generation.modelUnavailable'),
      })
      : (item.description || item.name),
    disabled: item.available === false,
  }))
  const active = node.run?.status === 'READY' || node.run?.status === 'RUNNING'

  function updateConfig(next: CanvasFunctionConfig, nextModelKey = modelKey) {
    setConfig(next)
    const version = draftVersionRef.current + 1
    draftVersionRef.current = version
    dirtyRef.current = true
    localSourceVersionsRef.current.set(
      functionSourceIdentity(nextModelKey, next),
      version,
    )
    if (!jsonDirty) {
      setJsonArgsText(JSON.stringify(next.rawArgs ?? next.parameters, null, 2))
      setJsonError(null)
    }
    runtime.scheduleFunctionConfig(node.id, nextModelKey, next)
  }

  /** 数值参数（整数走共享 NumberInput，小数保留原生 step=any 输入）共用同一校验。 */
  function applyNumberParameter(
    parameter: ReturnType<typeof extractParametersFromDefinition>[number],
    raw: string,
  ) {
    const value = raw.trim() === '' ? Number.NaN : Number(raw)
    if (
      !Number.isFinite(value)
      || (parameter.isInteger && !Number.isInteger(value))
      || (parameter.min !== null && value < parameter.min)
      || (parameter.max !== null && value > parameter.max)
    ) {
      return
    }
    updateConfig({
      ...config,
      parameters: {
        ...config.parameters,
        [parameter.key]: value,
      },
    })
  }

  function changeModel(nextName: string) {
    const nextModel = runtime.models.find((item) => item.name === nextName)
    if (!nextModel) {
      return
    }
    const defaults = createDefaultFunctionConfig(nextModel)
    const nextCandidates = referenceCandidates(snapshot, node.id, nextModel)
    const nextConfig = filterConfigReferences({
      prompt: config.prompt,
      references: config.references,
      parameters: defaults.parameters,
    }, nextCandidates, nextModel)
    setModelKey(nextModel.name)
    updateConfig(nextConfig, nextModel.name)
  }

  function updatePrompt(promptText: string) {
    updateConfig({
      ...config,
      prompt: promptText,
    })
  }

  function toggleCandidate(candidate: ReferenceCandidate) {
    const key = referenceKey(candidate.nodeId, candidate.index)
    if (referencedKeys.has(key)) {
      const nextRefs = config.references.filter((ref) => referenceKey(ref.nodeId, ref.index) !== key)
      updateConfig({ ...config, references: nextRefs })
    } else {
      if (!canAddReference(config.references, candidate, candidates, activeModel)) {
        runtime.setToast(t('canvas.generation.referenceLimit'))
        return
      }
      const nextRefs = [
        ...config.references,
        { type: 'resource' as const, nodeId: candidate.nodeId, index: candidate.index },
      ]
      updateConfig({ ...config, references: nextRefs })
    }
  }

  function removeReference(nodeId: UUIDString, index: number) {
    const key = referenceKey(nodeId, index)
    const nextRefs = config.references.filter((ref) => referenceKey(ref.nodeId, ref.index) !== key)
    updateConfig({ ...config, references: nextRefs })
  }

  return (
    <section
      className={`generation-panel ${expanded ? 'expanded' : ''}`}
      aria-label={t('canvas.generation.panelAria')}
      data-placement={panelPosition?.placement}
      ref={panelRef}
      style={panelStyle}
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="generation-panel-head">
        <div>
          <span className="generation-node-kicker">{t('canvas.generation.nodeKicker')}</span>
          <strong>{model.description || model.name}</strong>
          <span className={`generation-node-status ${node.run?.status.toLowerCase() ?? 'ready'}`}>
            {node.run
              ? t(runStatusKey(node.run.status))
              : t('canvas.generation.status.ready')}
          </span>
        </div>
        <div className="generation-panel-actions">
          <button
            type="button"
            aria-label={t(expanded ? 'canvas.generation.collapse' : 'canvas.generation.expand')}
            aria-expanded={expanded}
            onClick={() => setExpanded((current) => !current)}
          >
            ↔
          </button>
          <button
            className="close-panel"
            type="button"
            aria-label={t('canvas.generation.close')}
            onClick={() => runtime.setSelection([])}
          >
            ×
          </button>
        </div>
      </div>

      {config.rawArgs ? (
        <div className="generation-raw-editor" style={{ padding: '8px 12px' }}>
          {config.rawError ? (
            <div role="alert" className="generation-json-error" style={{ color: 'var(--color-danger, #ef4444)', fontSize: 12, marginBottom: 8 }}>
              {config.rawError}
            </div>
          ) : null}
          <span className="generation-prompt-label" style={{ marginBottom: 4, display: 'block' }}>完整 JSON 配置</span>
          <textarea
            aria-label="参数 JSON"
            className="generation-json-textarea"
            style={{ width: '100%', minHeight: 180, fontFamily: 'monospace', fontSize: 12, padding: 8 }}
            value={jsonArgsText}
            onChange={(e) => {
              const text = e.target.value
              setJsonArgsText(text)
              setJsonDirty(true)
              try {
                const parsed = JSON.parse(text)
                if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                  setJsonError(null)
                  updateConfig({
                    ...config,
                    rawArgs: parsed,
                    rawError: undefined,
                  })
                } else {
                  setJsonError('参数必须为 JSON 对象')
                }
              } catch {
                setJsonError('JSON 语法错误')
              }
            }}
          />
          {jsonError ? (
            <div role="alert" className="generation-json-error" style={{ color: 'var(--color-danger, #ef4444)', fontSize: 11, marginTop: 4 }}>
              {jsonError}
            </div>
          ) : null}
        </div>
      ) : (
        <>
          {candidates.length > 0 ? (
            <div className="generation-reference-row" aria-label={t('canvas.generation.references')}>
              {candidates.map((candidate) => (
                <button
                  key={referenceKey(candidate.nodeId, candidate.index)}
                  type="button"
                  className={`generation-reference ${
                    referencedKeys.has(referenceKey(candidate.nodeId, candidate.index))
                      ? 'referenced'
                      : ''
                  }`}
                  onClick={() => toggleCandidate(candidate)}
                  title={candidate.label}
                  aria-label={t('canvas.generation.referenceInsert', { label: candidate.label })}
                  aria-pressed={referencedKeys.has(referenceKey(candidate.nodeId, candidate.index))}
                >
                  <CanvasResourceThumbnail resource={candidate.resource} />
                  <span>{candidate.label}</span>
                </button>
              ))}
            </div>
          ) : null}

          {config.references.length > 0 ? (
            <div className="generation-attached-references" aria-label="已添加参考">
              {config.references.map((ref) => {
                const candidate = candidateByKey.get(referenceKey(ref.nodeId, ref.index))
                const label = candidate?.label ?? `@${ref.nodeId}_${ref.index}`
                return (
                  <span key={referenceKey(ref.nodeId, ref.index)} className="generation-attached-chip">
                    {candidate ? <CanvasResourceThumbnail resource={candidate.resource} /> : null}
                    <span>{label}</span>
                    <button
                      type="button"
                      aria-label={t('canvas.generation.referenceRemove', { label })}
                      onClick={() => removeReference(ref.nodeId, ref.index)}
                    >
                      ×
                    </button>
                  </span>
                )
              })}
            </div>
          ) : null}

          <span className="generation-prompt-label">{t('canvas.generation.promptLabel')}</span>
          <div className="prompt-segment-composer" aria-label={t('canvas.generation.composerAria')}>
            <textarea
              ref={promptInputRef}
              className="generation-prompt-input"
              aria-label={t('canvas.generation.promptLabel')}
              value={config.prompt ?? ''}
              placeholder={t('canvas.generation.placeholder')}
              disabled={active}
              onChange={(event) => updatePrompt(event.target.value)}
              rows={2}
            />
          </div>
        </>
      )}

      <div className="generation-footer">
        {activeModel.outputs && activeModel.outputs.length > 0 ? (
          <div className="generation-outputs-badge-list" aria-label="函数预期输出">
            {activeModel.outputs.map((out, idx) => (
              <span key={idx} className="generation-output-badge" title={out.name ? `${out.name} (${out.kind})` : out.kind}>
                {out.kind}{out.name ? `: ${out.name}` : ''}
              </span>
            ))}
          </div>
        ) : null}
        <div className="generation-controls">
          <div className="generation-control">
            <span className="sr-only">{t('canvas.generation.modelLabel')}</span>
            <Select
              aria-label={t('canvas.generation.modelLabel')}
              value={modelKey}
              disabled={active}
              options={modelSelectOptions}
              onChange={changeModel}
            />
          </div>
        {!config.rawArgs ? extractParametersFromDefinition(activeModel).map((parameter) => (
          <div className="generation-control" key={parameter.key}>
            <span>{parameter.label}</span>
            {parameter.type === 'ENUM' ? (
              <Select
                aria-label={parameter.label}
                value={String(config.parameters[parameter.key] ?? parameter.defaultValue ?? '')}
                disabled={active}
                options={parameter.options.map((option) => ({
                  value: String(option),
                  label: String(option),
                }))}
                onChange={(next) => {
                  const matched = parameter.options.find((opt) => String(opt) === next)
                  const nextVal = matched !== undefined ? matched : next
                  updateConfig({
                    ...config,
                    parameters: {
                      ...config.parameters,
                      [parameter.key]: nextVal,
                    },
                  })
                }}
              />
            ) : parameter.isInteger ? (
              <NumberInput
                aria-label={parameter.label}
                min={parameter.min ?? undefined}
                max={parameter.max ?? undefined}
                value={String(
                  config.parameters[parameter.key] ?? parameter.defaultValue ?? parameter.min ?? 0,
                )}
                disabled={active}
                onChange={(next) => applyNumberParameter(parameter, next)}
              />
            ) : (
              <input
                type="number"
                aria-label={parameter.label}
                min={parameter.min ?? undefined}
                max={parameter.max ?? undefined}
                step="any"
                value={Number(config.parameters[parameter.key] ?? parameter.defaultValue ?? parameter.min ?? 0)}
                disabled={active}
                onChange={(event) => applyNumberParameter(parameter, event.target.value)}
              />
            )}
          </div>
        )) : null}
        {!config.rawArgs ? (
          <div className="generation-json-toggle" style={{ marginTop: 8 }}>
            <button
              type="button"
              className="generation-subtle-btn"
              style={{ fontSize: 12, padding: '2px 6px', cursor: 'pointer' }}
              onClick={() => {
                if (!showJsonArgs) {
                  // 切换打开时从当前 config.parameters 生成，确保展示表单已改最新值
                  setJsonArgsText(JSON.stringify(config.parameters, null, 2))
                  setJsonDirty(false)
                  setJsonError(null)
                  setShowJsonArgs(true)
                } else {
                  setShowJsonArgs(false)
                  setJsonDirty(false)
                  setJsonError(null)
                }
              }}
            >
              {showJsonArgs ? '收起参数 JSON' : '编辑参数 JSON'}
            </button>
            {showJsonArgs ? (
              <>
                <textarea
                  aria-label="参数 JSON"
                  className="generation-json-textarea"
                  style={{ width: '100%', minHeight: 90, marginTop: 6, fontFamily: 'monospace', fontSize: 12 }}
                  value={jsonArgsText}
                  onChange={(e) => {
                    const text = e.target.value
                    setJsonArgsText(text)
                    setJsonDirty(true)
                    try {
                      const parsed = JSON.parse(text)
                      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                        setJsonError(null)
                        updateConfig({
                          ...config,
                          parameters: parsed,
                        })
                      } else {
                        setJsonError('参数必须为 JSON 对象')
                      }
                    } catch {
                      // 非法 JSON draft 保留在 textarea 中供用户继续修改，但绝不调用 updateConfig 覆盖有效表单值
                      setJsonError('JSON 语法错误')
                    }
                  }}
                />
                {jsonError ? (
                  <div role="alert" className="generation-json-error" style={{ color: 'var(--color-danger, #ef4444)', fontSize: 11, marginTop: 2 }}>
                    {jsonError}
                  </div>
                ) : null}
              </>
            ) : null}
          </div>
        ) : null}
        {node.run?.status === 'UNKNOWN' ? (
          <div className="generation-unknown-resolution" role="region" aria-label="待核查确认" style={{ marginTop: 10, padding: 8, border: '1px solid var(--orange, #fa8c16)', borderRadius: 4 }}>
            <div style={{ fontWeight: 600, color: 'var(--orange, #fa8c16)', marginBottom: 6 }}>
              待人工核查确认 (UNKNOWN)
            </div>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <Select
                aria-label="核查决定"
                value={unknownResolution}
                options={UNKNOWN_RESOLUTION_OPTIONS}
                onChange={(next) => setUnknownResolution(next as 'RESUME' | 'FAILED' | 'CANCELLED')}
              />
              <input
                type="text"
                aria-label="核查说明"
                placeholder="输入核查记录文本（必填）"
                value={verificationText}
                onChange={(e) => setVerificationText(e.target.value)}
                style={{ flex: 1, minWidth: 160 }}
              />
              <button
                type="button"
                className="generation-resolve-btn"
                disabled={!verificationText.trim()}
                onClick={() => {
                  if (node.run?.requestId && verificationText.trim()) {
                    void runtime.resolveFunctionRun(
                      node.id,
                      node.run.requestId,
                      unknownResolution,
                      verificationText.trim(),
                    )
                  }
                }}
              >
                提交核查
              </button>
            </div>
          </div>
        ) : null}
        {localPendingState ? (
          <div
            className="generation-local-pending-error"
            role="alert"
            data-testid="local-pending-error"
          >
            <strong>未决运行记录异常</strong>
            <p>{localPendingState.message}</p>
            {confirmingDiscard ? (
              <div
                className="generation-discard-confirm"
                role="alertdialog"
                aria-label="确认放弃未决记录"
              >
                <p>这不会撤销服务端运行，核实后再继续</p>
                <div className="generation-discard-confirm-actions">
                  <button
                    type="button"
                    onClick={() => setConfirmingDiscard(false)}
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    className="danger"
                    data-testid="confirm-discard-btn"
                    onClick={() => {
                      if (localPendingState.raw !== null) {
                        const success = runtime.discardPendingRun(node.id, localPendingState.raw)
                        if (success) {
                          setConfirmingDiscard(false)
                          setLocalPendingState(null)
                        }
                      }
                    }}
                  >
                    确认放弃
                  </button>
                </div>
              </div>
            ) : (
              <button
                type="button"
                className="generation-discard-btn"
                data-testid="discard-pending-btn"
                disabled={active || Boolean(runtime.isNodeInFlight?.(node.id))}
                onClick={() => setConfirmingDiscard(true)}
              >
                放弃本地未决记录
              </button>
            )}
          </div>
        ) : null}
        {node.run?.status === 'FAILED' ? (
          <span className="generation-run-feedback failed" role="alert">
            {node.run.error ?? t('canvas.generation.runFailed')}
          </span>
        ) : node.run ? (
          <span className={`generation-run-feedback ${node.run.status.toLowerCase()}`}>
            {runDisplayLabel(t, node.run.status, node.run.stage)}
          </span>
        ) : null}
        </div>
      </div>
    </section>
  )
}

function modelForNode(
  models: CanvasFunctionDefinitionDTO[],
  node: ResourceNode,
): CanvasFunctionDefinitionDTO | null {
  return models.find((model) => model.name === node.function?.name) ?? null
}

function functionSourceIdentity(modelKey: string, config: CanvasFunctionConfig): string {
  if (config.rawArgs) {
    return `${modelKey}\u0000raw\u0000${JSON.stringify(config.rawArgs)}`
  }
  return `${modelKey}\u0000${config.prompt ?? ''}\u0000${JSON.stringify(config.references)}\u0000${JSON.stringify(
    Object.fromEntries(Object.entries(config.parameters).sort(([left], [right]) => left.localeCompare(right))),
  )}`
}

function runStatusKey(status: NonNullable<ResourceNode['run']>['status']): string {
  if (status === 'READY') {
    return 'canvas.generation.status.ready'
  }
  if (status === 'RUNNING') {
    return 'canvas.generation.status.running'
  }
  if (status === 'FAILED') {
    return 'canvas.generation.status.failed'
  }
  if (status === 'CANCELLED') {
    return 'canvas.generation.status.cancelled'
  }
  if (status === 'UNKNOWN') {
    return 'canvas.generation.status.unknown'
  }
  return 'canvas.generation.status.succeeded'
}

function runDisplayLabel(
  t: (key: string, values?: Record<string, string | number>) => string,
  status: NonNullable<ResourceNode['run']>['status'],
  stage: string,
): string {
  if (status === 'UNKNOWN') {
    return stage && stage !== status ? stage : '待核查确认'
  }
  return stage && stage !== status ? stage : t(runStatusKey(status))
}
