import { useEffect, useMemo, useRef, useState } from 'react'
import {
  canAddReference,
  createDefaultFunctionConfig,
  extractParametersFromDefinition,
  filterConfigReferences,
  parseFunctionConfig,
  referenceCandidates,
  referenceKey,
  type CanvasFunctionParameterDefinition,
  type ReferenceCandidate,
} from '@/features/canvas/generation'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import type { CanvasFunctionConfig } from '@/features/canvas/types'
import type { CanvasFunctionDefinitionDTO } from '@/shared/api/generated/contracts'
import type { TranslationKey } from '@/shared/i18n'

export interface FunctionConfigDraftOptions {
  snapshot: CanvasSnapshot
  node: ResourceNode
  models: CanvasFunctionDefinitionDTO[]
  scheduleFunctionConfig: (nodeId: string, functionName: string, config: CanvasFunctionConfig) => false | void
  /** 新增引用超过模型上限时的提示出口。 */
  onReferenceLimit: () => void
}

/**
 * 面板内的一份函数配置草稿：模型选择、表单参数、引用与完整 JSON 通道。
 *
 * 本地编辑以单调递增的 version 与语义 source identity 标记，外部权威配置到达时：
 * 未编辑则整体替换，正在输入则只同步 references/parameters 等外部字段，绝不覆盖 prompt。
 */
export interface FunctionConfigDraft {
  modelKey: string
  /** 当前选中的目录定义；目录里查不到时为 null，面板此时不渲染。 */
  model: CanvasFunctionDefinitionDTO | null
  sourceModel: CanvasFunctionDefinitionDTO | null
  config: CanvasFunctionConfig
  parameters: CanvasFunctionParameterDefinition[]
  candidates: ReferenceCandidate[]
  candidateByKey: Map<string, ReferenceCandidate>
  referencedKeys: Set<string>
  jsonArgsText: string
  /** JSON 通道的校验失败提示（i18n key），语法非法时草稿仍留在 textarea。 */
  jsonError: TranslationKey | null
  showJsonArgs: boolean
  changeModel: (nextName: string) => void
  updatePrompt: (prompt: string) => void
  changeEnumParameter: (parameter: CanvasFunctionParameterDefinition, option: string) => void
  applyNumberParameter: (parameter: CanvasFunctionParameterDefinition, raw: string) => void
  toggleCandidate: (candidate: ReferenceCandidate) => void
  removeReference: (nodeId: string, index: number) => void
  changeRawJson: (text: string) => void
  changeParametersJson: (text: string) => void
  toggleJsonEditor: () => void
}

export function useFunctionConfigDraft({
  snapshot,
  node,
  models,
  scheduleFunctionConfig,
  onReferenceLimit,
}: FunctionConfigDraftOptions): FunctionConfigDraft {
  const sourceModel = models.find((model) => model.name === node.function?.name) ?? null
  const [modelKey, setModelKey] = useState(sourceModel?.name ?? node.function?.name ?? '')
  const model = models.find((item) => item.name === modelKey) ?? sourceModel
  const [config, setConfig] = useState<CanvasFunctionConfig>(() => initialConfig(node, sourceModel))
  const [showJsonArgs, setShowJsonArgs] = useState(false)
  const [jsonArgsText, setJsonArgsText] = useState(() => JSON.stringify(config.rawArgs ?? config.parameters, null, 2))
  const [jsonDirty, setJsonDirty] = useState(false)
  const [jsonError, setJsonError] = useState<TranslationKey | null>(null)

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
  const parameters = useMemo(
    () => model ? extractParametersFromDefinition(model) : [],
    [model],
  )

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
        rawErrorKey: sourceConfig.rawErrorKey,
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

  function updateConfig(next: CanvasFunctionConfig, nextModelKey = modelKey) {
    if (scheduleFunctionConfig(node.id, nextModelKey, next) === false) {
      return false
    }
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
  }

  /** 数值参数（整数走共享 NumberInput，小数保留原生 step=any 输入）共用同一校验。 */
  function applyNumberParameter(parameter: CanvasFunctionParameterDefinition, raw: string) {
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

  function changeEnumParameter(parameter: CanvasFunctionParameterDefinition, option: string) {
    const matched = parameter.options.find((candidate) => String(candidate) === option)
    updateConfig({
      ...config,
      parameters: {
        ...config.parameters,
        [parameter.key]: matched !== undefined ? matched : option,
      },
    })
  }

  function changeModel(nextName: string) {
    const nextModel = models.find((item) => item.name === nextName)
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
    if (updateConfig(nextConfig, nextModel.name) !== false) {
      setModelKey(nextModel.name)
    }
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
      updateConfig({ ...config, references: withoutReference(config.references, key) })
      return
    }
    if (!model || !canAddReference(config.references, candidate, candidates, model)) {
      onReferenceLimit()
      return
    }
    updateConfig({
      ...config,
      references: [
        ...config.references,
        { type: 'resource' as const, nodeId: candidate.nodeId, index: candidate.index },
      ],
    })
  }

  function removeReference(nodeId: string, index: number) {
    updateConfig({
      ...config,
      references: withoutReference(config.references, referenceKey(nodeId, index)),
    })
  }

  /** 完整 JSON 通道（模型不声明字符串 prompt / 入参结构非常规时使用）：解析结果写回 rawArgs。 */
  function changeRawJson(text: string) {
    editJsonArgs(text, (parsed) => updateConfig({ ...config, rawArgs: parsed, rawErrorKey: undefined }))
  }

  /** 参数 JSON 通道：非法草稿只留在 textarea，绝不覆盖仍然有效的表单值。 */
  function changeParametersJson(text: string) {
    editJsonArgs(text, (parsed) => updateConfig({ ...config, parameters: parsed }))
  }

  function editJsonArgs(text: string, applyParsed: (parsed: Record<string, unknown>) => false | void) {
    let parsed: unknown
    let error: TranslationKey | null = null
    try {
      parsed = JSON.parse(text)
    } catch {
      error = 'canvas.generation.jsonSyntaxError'
    }
    if (!error) {
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        if (applyParsed(parsed as Record<string, unknown>) === false) {
          return
        }
      } else {
        error = 'canvas.generation.jsonObjectRequired'
      }
    }
    setJsonArgsText(text)
    setJsonDirty(true)
    setJsonError(error)
  }

  function toggleJsonEditor() {
    if (!showJsonArgs) {
      // 切换打开时从当前 parameters 生成，确保展示表单已改最新值
      setJsonArgsText(JSON.stringify(config.parameters, null, 2))
      setJsonDirty(false)
      setJsonError(null)
      setShowJsonArgs(true)
    } else {
      setShowJsonArgs(false)
      setJsonDirty(false)
      setJsonError(null)
    }
  }

  return {
    modelKey,
    model,
    sourceModel,
    config,
    parameters,
    candidates,
    candidateByKey,
    referencedKeys,
    jsonArgsText,
    jsonError,
    showJsonArgs,
    changeModel,
    updatePrompt,
    changeEnumParameter,
    applyNumberParameter,
    toggleCandidate,
    removeReference,
    changeRawJson,
    changeParametersJson,
    toggleJsonEditor,
  }
}

function initialConfig(
  node: ResourceNode,
  sourceModel: CanvasFunctionDefinitionDTO | null,
): CanvasFunctionConfig {
  if (sourceModel && node.function) {
    return parseFunctionConfig(node.function.args, sourceModel)
  }
  return sourceModel
    ? createDefaultFunctionConfig(sourceModel)
    : { prompt: '', references: [], parameters: {} }
}

function withoutReference(
  references: CanvasFunctionConfig['references'],
  key: string,
): CanvasFunctionConfig['references'] {
  return references.filter((ref) => referenceKey(ref.nodeId, ref.index) !== key)
}

function functionSourceIdentity(modelKey: string, config: CanvasFunctionConfig): string {
  if (config.rawArgs) {
    return `${modelKey}\u0000raw\u0000${JSON.stringify(config.rawArgs)}`
  }
  const parameters = Object.fromEntries(
    Object.entries(config.parameters).sort(([left], [right]) => left.localeCompare(right)),
  )
  return `${modelKey}\u0000${config.prompt ?? ''}\u0000${JSON.stringify(config.references)}\u0000${JSON.stringify(parameters)}`
}
