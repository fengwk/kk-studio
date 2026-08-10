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
  canInsertReference,
  createDefaultFunctionConfig,
  filterConfigReferences,
  insertReferenceAtCursor,
  parseFunctionConfig,
  promptVisibleText,
  referenceCandidates,
  referenceKey,
  removePromptSegment,
  updateTextSegment,
  type PromptCursor,
  type ReferenceCandidate,
} from '@/features/canvas/generation'
import { CanvasResourceThumbnail } from '@/features/canvas/nodes/CanvasResourceMedia'
import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'
import { useI18n } from '@/shared/i18n'
import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
  CanvasTransformDTO,
  PromptSegmentDTO,
} from '@/shared/api/contracts/studio'

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
  const [modelKey, setModelKey] = useState(sourceModel?.key ?? node.function?.modelKey ?? '')
  const model = runtime.models.find((item) => item.key === modelKey) ?? sourceModel
  const [expanded, setExpanded] = useState(false)
  const [config, setConfig] = useState<CanvasFunctionConfigDTO>(() => (
    sourceModel && node.function
      ? parseFunctionConfig(node.function.configJson, sourceModel)
      : sourceModel
        ? createDefaultFunctionConfig(sourceModel)
        : { prompt: { segments: [{ type: 'TEXT', text: '' }] }, parameters: {} }
  ))
  const [mentionMenuOpen, setMentionMenuOpen] = useState(false)
  const [panelSize, setPanelSize] = useState({ width: 560, height: 190 })
  const panelRef = useRef<HTMLElement | null>(null)
  const inputRefs = useRef(new Map<number, HTMLInputElement>())
  const cursorRef = useRef<PromptCursor>({ segmentIndex: 0, offset: 0 })
  const pendingFocusRef = useRef<PromptCursor | null>(null)
  // Every local edit gets a monotonic revision and semantic source identity.
  // Matching snapshots acknowledge that revision; only genuinely external sources may replace a newer draft.
  const draftRevisionRef = useRef(0)
  const dirtyRef = useRef(false)
  const sourceRef = useRef<{ identity: string; modelSignature: string } | null>(null)
  const localSourceRevisionsRef = useRef(new Map<string, number>())
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
  const referencedKeys = useMemo(() => new Set(config.prompt.segments
    .filter((segment): segment is Extract<PromptSegmentDTO, { type: 'REFERENCE' }> => (
      segment.type === 'REFERENCE'
    ))
    .map((segment) => referenceKey(segment.nodeId, segment.index))), [config.prompt.segments])
  const panelPosition = useMemo(() => (
    anchor && anchor.stage.width > 900
      ? generationPanelPosition({
        node: anchor.node,
        viewport: anchor.viewport,
        stage: anchor.stage,
        panel: panelSize,
      })
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

  useLayoutEffect(() => {
    const pending = pendingFocusRef.current
    if (!pending) {
      return
    }
    const input = inputRefs.current.get(pending.segmentIndex)
    if (!input) {
      return
    }
    input.focus()
    input.setSelectionRange(pending.offset, pending.offset)
    cursorRef.current = pending
    pendingFocusRef.current = null
  }, [config.prompt.segments])

  const sourceConfig = useMemo(() => (
    node.function && sourceModel
      ? parseFunctionConfig(node.function.configJson, sourceModel)
      : null
  ), [node.function, sourceModel])
  const sourceIdentity = node.function && sourceConfig
    ? functionSourceIdentity(node.function.modelKey, sourceConfig)
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
    const acknowledgedRevision = localSourceRevisionsRef.current.get(sourceIdentity)
    if (acknowledgedRevision !== undefined) {
      for (const [identity, revision] of localSourceRevisionsRef.current) {
        if (revision <= acknowledgedRevision) {
          localSourceRevisionsRef.current.delete(identity)
        }
      }
      if (dirtyRef.current && draftRevisionRef.current > acknowledgedRevision) {
        return
      }
    } else {
      localSourceRevisionsRef.current.clear()
    }
    setModelKey(node.function.modelKey)
    setConfig(sourceConfig)
    cursorRef.current = { segmentIndex: 0, offset: 0 }
    dirtyRef.current = false
  }, [
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
    if (JSON.stringify(filtered.prompt.segments) !== JSON.stringify(config.prompt.segments)) {
      updateConfig(filtered)
    }
    // Candidate changes are the only reason for this reconciliation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candidates, model])

  if (!node.function || !model) {
    return null
  }

  const activeModel = model
  const outputKind = sourceModel?.outputKind ?? activeModel.outputKind
  const modelOptions = runtime.models.filter((item) => item.outputKind === outputKind)
  const running = node.run?.status === 'RUNNING'
  const promptValid = Boolean(promptVisibleText(config.prompt.segments).trim())

  function updateConfig(next: CanvasFunctionConfigDTO, nextModelKey = modelKey) {
    setConfig(next)
    const revision = draftRevisionRef.current + 1
    draftRevisionRef.current = revision
    dirtyRef.current = true
    localSourceRevisionsRef.current.set(
      functionSourceIdentity(nextModelKey, next),
      revision,
    )
    runtime.scheduleFunctionConfig(node.id, nextModelKey, next)
  }

  function updatePrompt(segments: PromptSegmentDTO[]) {
    updateConfig({
      ...config,
      prompt: { segments },
    })
  }

  function insertCandidate(candidate: ReferenceCandidate) {
    if (!canInsertReference(config.prompt.segments, candidate, candidates, activeModel)) {
      runtime.setToast(t('canvas.generation.referenceLimit'))
      return
    }
    const next = insertReferenceAtCursor(config.prompt.segments, cursorRef.current, candidate)
    const nextCursor = {
      segmentIndex: Math.min(cursorRef.current.segmentIndex + 2, next.length - 1),
      offset: 0,
    }
    cursorRef.current = nextCursor
    pendingFocusRef.current = nextCursor
    updatePrompt(next)
    setMentionMenuOpen(false)
  }

  function removeReferenceAt(
    segmentIndex: number,
    focus: PromptCursor | null = null,
  ) {
    if (focus) {
      cursorRef.current = focus
      pendingFocusRef.current = focus
    }
    updatePrompt(removePromptSegment(config.prompt.segments, segmentIndex))
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
          <strong>{model.label}</strong>
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
              onClick={() => insertCandidate(candidate)}
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

      <span className="generation-prompt-label">{t('canvas.generation.promptLabel')}</span>
      <div className="prompt-segment-composer" aria-label={t('canvas.generation.composerAria')}>
        {config.prompt.segments.map((segment, index) => (
          segment.type === 'TEXT' ? (
            <input
              key={`text:${index}`}
              type="text"
              ref={(element) => {
                if (element) {
                  inputRefs.current.set(index, element)
                } else {
                  inputRefs.current.delete(index)
                }
              }}
              value={segment.text}
              aria-label={t('canvas.generation.segmentAria', { index: index + 1 })}
              placeholder={config.prompt.segments.length === 1 ? t('canvas.generation.placeholder') : ''}
              style={{ flexGrow: Math.max(2, Math.min(20, segment.text.length || 2)) }}
              onFocus={(event) => {
                cursorRef.current = {
                  segmentIndex: index,
                  offset: event.currentTarget.selectionStart ?? segment.text.length,
                }
              }}
              onSelect={(event) => {
                cursorRef.current = {
                  segmentIndex: index,
                  offset: event.currentTarget.selectionStart ?? segment.text.length,
                }
              }}
              onChange={(event) => {
                const caret = event.currentTarget.selectionStart ?? event.currentTarget.value.length
                let text = event.currentTarget.value
                if (caret > 0 && text[caret - 1] === '@') {
                  text = `${text.slice(0, caret - 1)}${text.slice(caret)}`
                  cursorRef.current = { segmentIndex: index, offset: caret - 1 }
                  setMentionMenuOpen(true)
                } else {
                  cursorRef.current = { segmentIndex: index, offset: caret }
                }
                updatePrompt(updateTextSegment(config.prompt.segments, index, text))
              }}
              onKeyDown={(event) => {
                const start = event.currentTarget.selectionStart ?? 0
                const end = event.currentTarget.selectionEnd ?? start
                if (
                  event.key === 'Backspace'
                  && start === 0
                  && end === 0
                  && config.prompt.segments[index - 1]?.type === 'REFERENCE'
                ) {
                  event.preventDefault()
                  const previousText = config.prompt.segments[index - 2]
                  removeReferenceAt(index - 1, {
                    segmentIndex: Math.max(0, index - 2),
                    offset: previousText?.type === 'TEXT' ? previousText.text.length : 0,
                  })
                } else if (
                  event.key === 'Delete'
                  && start === segment.text.length
                  && end === start
                  && config.prompt.segments[index + 1]?.type === 'REFERENCE'
                ) {
                  event.preventDefault()
                  removeReferenceAt(index + 1, {
                    segmentIndex: index,
                    offset: segment.text.length,
                  })
                }
              }}
            />
          ) : (
            <button
              key={`reference:${index}:${segment.nodeId}:${segment.index}`}
              type="button"
              className="prompt-mention"
              aria-label={t('canvas.generation.referenceRemove', { label: candidateByKey.get(referenceKey(segment.nodeId, segment.index))?.label ?? `@${segment.nodeId}[${segment.index}]` })}
              onClick={() => removeReferenceAt(index)}
              onKeyDown={(event) => {
                if (event.key === 'Delete' || event.key === 'Backspace') {
                  event.preventDefault()
                  removeReferenceAt(index)
                }
              }}
            >
              {candidateByKey.get(referenceKey(segment.nodeId, segment.index))?.label
                ?? `@${segment.nodeId}[${segment.index}]`}
              <span aria-hidden="true">×</span>
            </button>
          )
        ))}
        {mentionMenuOpen ? (
          <div className="mention-candidates" role="listbox" aria-label={t('canvas.generation.referenceCandidates')}>
            {candidates.map((candidate) => (
              <button
                key={referenceKey(candidate.nodeId, candidate.index)}
                type="button"
                role="option"
                aria-selected="false"
                onClick={() => insertCandidate(candidate)}
              >
                <CanvasResourceThumbnail resource={candidate.resource} />
                <span>{candidate.label}</span>
              </button>
            ))}
            {candidates.length === 0 ? <span>{t('canvas.generation.referenceNone')}</span> : null}
          </div>
        ) : null}
      </div>

      <div className="generation-footer">
        <div className="generation-controls">
          <label>
            <span className="sr-only">{t('canvas.generation.modelLabel')}</span>
            <select
              aria-label={t('canvas.generation.modelLabel')}
              value={modelKey}
              disabled={running}
              onChange={(event) => {
                const nextModel = runtime.models.find((item) => item.key === event.target.value)
                if (!nextModel) {
                  return
                }
                const defaults = createDefaultFunctionConfig(nextModel)
                const nextCandidates = referenceCandidates(snapshot, node.id, nextModel)
                const nextConfig = filterConfigReferences({
                  prompt: {
                    segments: config.prompt.segments.map((segment) => ({ ...segment })),
                  },
                  parameters: defaults.parameters,
                }, nextCandidates, nextModel)
                setModelKey(nextModel.key)
                updateConfig(nextConfig, nextModel.key)
              }}
            >
              {modelOptions.map((item) => (
                <option key={item.key} value={item.key} disabled={!item.available}>
                {item.available
                  ? item.label
                  : t('canvas.generation.modelUnavailableOption', {
                    label: item.label,
                    reason: item.unavailableReason ?? t('canvas.generation.modelUnavailable'),
                  })}
                </option>
              ))}
            </select>
          </label>
        {activeModel.parameters.map((parameter) => (
          <label key={parameter.key}>
            <span>{parameter.label}</span>
            {parameter.type === 'ENUM' ? (
              <select
                aria-label={parameter.label}
                value={String(config.parameters[parameter.key] ?? '')}
                disabled={running}
                onChange={(event) => updateConfig({
                  ...config,
                  parameters: {
                    ...config.parameters,
                    [parameter.key]: event.target.value,
                  },
                })}
              >
                {parameter.options.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            ) : (
              <input
                type="number"
                aria-label={parameter.label}
                min={parameter.min ?? undefined}
                max={parameter.max ?? undefined}
                step={1}
                value={Number(config.parameters[parameter.key] ?? parameter.min ?? 0)}
                disabled={running}
                onChange={(event) => {
                  const value = Number(event.target.value)
                  if (
                    !Number.isInteger(value)
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
                }}
              />
            )}
          </label>
        ))}
        {node.run?.status === 'FAILED' ? (
          <span className="generation-run-feedback failed" role="alert">
            {node.run.error ?? t('canvas.generation.runFailed')}
          </span>
        ) : node.run ? (
          <span className={`generation-run-feedback ${node.run.status.toLowerCase()}`}>
            {runDisplayLabel(t, node.run.status, node.run.stage)}
          </span>
        ) : null}
        {running ? (
          <button
            type="button"
            className="generation-submit cancel"
            onClick={() => void runtime.cancelFunctionRun(node.id, node.run?.requestId ?? '')}
          >
            {t('canvas.generation.cancel')}
          </button>
        ) : (
          <button
            type="button"
            className="generation-submit"
            aria-label={t('canvas.generation.submit')}
            disabled={!activeModel.available || !promptValid}
            onClick={() => void runtime.startFunctionRun(node.id)}
          >
            ↑
          </button>
        )}
        </div>
      </div>
    </section>
  )
}

function modelForNode(
  models: CanvasFunctionModelDTO[],
  node: ResourceNode,
): CanvasFunctionModelDTO | null {
  return models.find((model) => model.key === node.function?.modelKey) ?? null
}

function functionSourceIdentity(modelKey: string, config: CanvasFunctionConfigDTO): string {
  return `${modelKey}\u0000${JSON.stringify({
    prompt: config.prompt,
    parameters: Object.fromEntries(Object.entries(config.parameters).sort(([left], [right]) => (
      left.localeCompare(right)
    ))),
  })}`
}

function runStatusKey(status: NonNullable<ResourceNode['run']>['status']): string {
  if (status === 'RUNNING') {
    return 'canvas.generation.status.running'
  }
  if (status === 'FAILED') {
    return 'canvas.generation.status.failed'
  }
  if (status === 'CANCELLED') {
    return 'canvas.generation.status.cancelled'
  }
  return 'canvas.generation.status.succeeded'
}

function runDisplayLabel(
  t: (key: string, values?: Record<string, string | number>) => string,
  status: NonNullable<ResourceNode['run']>['status'],
  stage: string,
): string {
  return stage && stage !== status ? stage : t(runStatusKey(status))
}
