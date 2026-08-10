import { useEffect, useMemo, useRef, useState } from 'react'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
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
import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
  PromptSegmentDTO,
} from '@/shared/api/contracts/studio'

export function CanvasGenerationPanel({
  snapshot,
  node,
}: {
  snapshot: CanvasSnapshot
  node: ResourceNode
}) {
  const runtime = useCanvasRuntime()
  const { flushFunctionConfig } = runtime
  const initialModel = modelForNode(runtime.models, node)
  const [modelKey, setModelKey] = useState(initialModel?.key ?? node.function?.modelKey ?? '')
  const model = runtime.models.find((item) => item.key === modelKey) ?? initialModel
  const [config, setConfig] = useState<CanvasFunctionConfigDTO>(() => (
    initialModel && node.function
      ? parseFunctionConfig(node.function.configJson, initialModel)
      : initialModel
        ? createDefaultFunctionConfig(initialModel)
        : { prompt: { segments: [{ type: 'TEXT', text: '' }] }, parameters: {} }
  ))
  const [mentionMenuOpen, setMentionMenuOpen] = useState(false)
  const cursorRef = useRef<PromptCursor>({ segmentIndex: 0, offset: 0 })
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

  useEffect(() => () => {
    void flushFunctionConfig(node.id)
  }, [flushFunctionConfig, node.id])

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
  const outputKind = initialModel?.outputKind ?? activeModel.outputKind
  const modelOptions = runtime.models.filter((item) => item.outputKind === outputKind)
  const running = node.run?.status === 'RUNNING'
  const promptValid = Boolean(promptVisibleText(config.prompt.segments).trim())

  function updateConfig(next: CanvasFunctionConfigDTO, nextModelKey = modelKey) {
    setConfig(next)
    if (promptVisibleText(next.prompt.segments).trim()) {
      runtime.scheduleFunctionConfig(node.id, nextModelKey, next)
    }
  }

  function updatePrompt(segments: PromptSegmentDTO[]) {
    updateConfig({
      ...config,
      prompt: { segments },
    })
  }

  function insertCandidate(candidate: ReferenceCandidate) {
    if (!canInsertReference(config.prompt.segments, candidate, candidates, activeModel)) {
      runtime.setToast('当前模型的参考资源数量已达到上限。')
      return
    }
    const next = insertReferenceAtCursor(config.prompt.segments, cursorRef.current, candidate)
    updatePrompt(next)
    setMentionMenuOpen(false)
  }

  return (
    <section
      className="generation-panel"
      aria-label="Function 生成面板"
      onPointerDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
    >
      <div className="generation-reference-row" aria-label="可用参考资源">
        {candidates.length > 0 ? candidates.map((candidate) => (
          <button
            key={referenceKey(candidate.nodeId, candidate.index)}
            type="button"
            className="generation-reference"
            onClick={() => insertCandidate(candidate)}
            title={candidate.label}
            aria-label={`插入参考 ${candidate.label}`}
          >
            <CanvasResourceThumbnail resource={candidate.resource} />
            <span>{candidate.label}</span>
          </button>
        )) : (
          <span className="generation-reference-empty">连接 ResourceNode 后可在这里引用</span>
        )}
      </div>

      <div className="prompt-segment-composer" aria-label="结构化提示词">
        {config.prompt.segments.map((segment, index) => (
          segment.type === 'TEXT' ? (
            <input
              key={`text:${index}`}
              type="text"
              value={segment.text}
              aria-label={`提示词片段 ${index + 1}`}
              placeholder={config.prompt.segments.length === 1 ? '描述你想生成的内容，输入 @ 引用资源' : ''}
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
            />
          ) : (
            <button
              key={`reference:${index}:${segment.nodeId}:${segment.index}`}
              type="button"
              className="prompt-mention"
              aria-label={`删除 ${candidateByKey.get(referenceKey(segment.nodeId, segment.index))?.label ?? `@${segment.nodeId}[${segment.index}]`}`}
              onClick={() => updatePrompt(removePromptSegment(config.prompt.segments, index))}
            >
              {candidateByKey.get(referenceKey(segment.nodeId, segment.index))?.label
                ?? `@${segment.nodeId}[${segment.index}]`}
              <span aria-hidden="true">×</span>
            </button>
          )
        ))}
        {mentionMenuOpen ? (
          <div className="mention-candidates" role="listbox" aria-label="@ 引用候选">
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
            {candidates.length === 0 ? <span>没有可用引用</span> : null}
          </div>
        ) : null}
      </div>

      <div className="generation-controls">
        <label>
          <span className="sr-only">模型</span>
          <select
            aria-label="模型"
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
                {item.label}
                {item.available ? '' : `（${item.unavailableReason ?? '不可用'}）`}
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
                value={Number(config.parameters[parameter.key] ?? parameter.min ?? 0)}
                disabled={running}
                onChange={(event) => updateConfig({
                  ...config,
                  parameters: {
                    ...config.parameters,
                    [parameter.key]: Number(event.target.value),
                  },
                })}
              />
            )}
          </label>
        ))}
        {node.run?.status === 'FAILED' ? (
          <span className="generation-run-feedback failed" role="alert">
            {node.run.error ?? '生成失败，已有资源已保留'}
          </span>
        ) : node.run ? (
          <span className={`generation-run-feedback ${node.run.status.toLowerCase()}`}>
            {node.run.stage || node.run.status}
          </span>
        ) : null}
        {running ? (
          <button
            type="button"
            className="generation-submit cancel"
            onClick={() => void runtime.cancelFunctionRun(node.id, node.run?.requestId ?? '')}
          >
            取消
          </button>
        ) : (
          <button
            type="button"
            className="generation-submit"
            aria-label="开始生成"
            disabled={!activeModel.available || !promptValid}
            onClick={() => void runtime.startFunctionRun(node.id)}
          >
            ↑
          </button>
        )}
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
