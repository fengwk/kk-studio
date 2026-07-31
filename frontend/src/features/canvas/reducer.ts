import {
  createInitialLinks,
  createInitialNodes,
  DEFAULT_CONTEXT_IDS,
  DEFAULT_VIEWPORT,
  GENERATION_PROFILES,
} from '@/features/canvas/data'
import { translate } from '@/shared/i18n'
import {
  clampZoom,
  findOpenCanvasPosition,
  preferredCreatePosition,
  revealNodeViewportShift,
  viewportsEqual,
} from '@/features/canvas/geometry'
import type {
  AddMenuAction,
  AgentContextMode,
  AgentRunNode,
  CanvasDocumentState,
  CanvasNode,
  CanvasTool,
  CanvasViewport,
  CanvasView,
  GenerationMode,
  GeneratorNode,
  LibraryFilter,
  ResearchTab,
  ResultNode,
  StageMetrics,
  ThreadMessage,
} from '@/features/canvas/types'

export function createInitialCanvasState(): CanvasDocumentState {
  return {
    nodes: createInitialNodes(),
    links: createInitialLinks(),
    selectedIds: [],
    viewport: { ...DEFAULT_VIEWPORT },
    contextMode: 'selection',
    tool: 'select',
    messages: [],
    activeGeneratorId: null,
    generationPanelExpanded: false,
    sequence: 0,
    saveState: 'saved',
    toast: null,
    libraryFilter: 'all',
    selectedTemplate: '研究与归纳',
    idea: '整理竞品资料，输出产品定位、功能矩阵和 MVP 原型',
    view: 'library',
    researchOpen: false,
    researchTab: 'research',
    helpOpen: false,
    threadOpen: false,
    addMenuOpen: false,
    addMenuIndex: 0,
    agentPrompt: '',
    generationPromptDraft: '',
    forceThreadScroll: false,
    focusAgentPromptToken: 0,
    focusGenerationPromptToken: 0,
  }
}

export type CanvasAction =
  | { type: 'set-view'; view: CanvasView }
  | { type: 'set-library-filter'; filter: LibraryFilter }
  | { type: 'set-selected-template'; template: string }
  | { type: 'set-idea'; idea: string }
  | { type: 'set-toast'; toast: string | null }
  | { type: 'set-research-open'; open: boolean }
  | { type: 'set-research-tab'; tab: ResearchTab }
  | { type: 'set-help-open'; open: boolean }
  | { type: 'set-thread-open'; open: boolean }
  | { type: 'set-add-menu-open'; open: boolean }
  | { type: 'set-add-menu-index'; index: number }
  | { type: 'set-agent-prompt'; value: string }
  | { type: 'set-generation-prompt'; value: string }
  | { type: 'set-context-mode'; mode: AgentContextMode }
  | { type: 'set-tool'; tool: CanvasTool; silent?: boolean }
  | { type: 'set-viewport'; viewport: CanvasViewport }
  | { type: 'set-selection'; ids: string[] }
  | { type: 'clear-selection' }
  | { type: 'set-save-state'; saveState: 'saved' | 'saving' }
  | { type: 'move-nodes'; updates: Array<{ id: string; x: number; y: number }> }
  | { type: 'delete-selection' }
  | { type: 'activate-generator'; id: string | null; focusPrompt?: boolean }
  | { type: 'toggle-generation-expanded' }
  | { type: 'set-generation-capability'; capability: string }
  | { type: 'cycle-parameter'; index: number }
  | { type: 'toggle-reference'; index: number }
  | { type: 'create-generator'; mode: GenerationMode; stage: StageMetrics }
  | { type: 'submit-generation' }
  | { type: 'handle-add-action'; action: AddMenuAction; stage: StageMetrics }
  | { type: 'create-text-node'; stage: StageMetrics }
  | { type: 'send-agent-message' }
  | { type: 'pause-agent-run' }
  | { type: 'resume-agent-run' }
  | { type: 'retry-agent-run' }
  | { type: 'tick-agent-run'; stage: StageMetrics }
  | { type: 'finish-agent-run'; stage: StageMetrics }
  | { type: 'reset-demo' }
  | { type: 'consume-thread-scroll' }
  | { type: 'open-editor-from-idea' }
  | { type: 'mark-generator-draft-from-prompt' }
  | { type: 'focus-agent-prompt' }

function nextId(state: CanvasDocumentState, prefix: string): { id: string; sequence: number } {
  const sequence = state.sequence + 1
  return { id: `${prefix}-${Date.now()}-${sequence}`, sequence }
}

function findNode(nodes: CanvasNode[], id: string | null | undefined): CanvasNode | undefined {
  if (!id) {
    return undefined
  }
  return nodes.find((node) => node.id === id)
}

function findRun(nodes: CanvasNode[]): AgentRunNode | undefined {
  const node = nodes.find((item) => item.type === 'run')
  return node && node.type === 'run' ? node : undefined
}

function findGenerator(nodes: CanvasNode[], id: string | null): GeneratorNode | null {
  const node = findNode(nodes, id)
  return node && node.type === 'generator' ? node : null
}

function generatorStatusLabel(status: GeneratorNode['status']): string {
  return translate(status === 'generated' ? 'canvas.generation.status.generated' : 'canvas.generation.status.draft')
}

function generatorTitle(mode: GenerationMode, status: GeneratorNode['status']): string {
  const suffix = translate(
    status === 'generated' ? 'canvas.generation.title.result' : 'canvas.generation.title.draft',
  )
  return `${translate(GENERATION_PROFILES[mode].labelKey)} · ${suffix}`
}

function generatorSummary(prompt: string): string {
  const normalized = prompt.trim()
  return normalized.length > 54 ? `${normalized.slice(0, 54)}…` : normalized
}

function getParameterSummary(node: GeneratorNode): string {
  const profile = GENERATION_PROFILES[node.generationMode]
  return profile.groups
    .map((group, index) => {
      const value = group.values[node.parameterIndexes[index] ?? 0] ?? group.values[0]
      return translate(value)
    })
    .join(' · ')
}

function initializeGeneratorNode(
  base: { id: string; x: number; y: number },
  mode: GenerationMode,
): GeneratorNode {
  const profile = GENERATION_PROFILES[mode]
  const prompt = profile.prompt
  return {
    id: base.id,
    type: 'generator',
    domainKind: 'FUNCTION',
    x: base.x,
    y: base.y,
    width: profile.size.width,
    height: profile.size.height,
    generationMode: mode,
    prompt,
    parameterIndexes: profile.groups.map(() => 0),
    references: [true, true, false],
    capability: profile.capabilities[0],
    status: 'draft',
    title: generatorTitle(mode, 'draft'),
    copy: generatorSummary(prompt),
    meta: `${translate(profile.capabilities[0])} · ${generatorStatusLabel('draft')}`,
  }
}

function markSaving(state: CanvasDocumentState): CanvasDocumentState {
  return { ...state, saveState: 'saving' }
}

function withToast(state: CanvasDocumentState, toast: string): CanvasDocumentState {
  return { ...state, toast }
}

function closeOverlaysExcept(
  state: CanvasDocumentState,
  keep: 'thread' | 'add-menu' | 'generation' | 'none',
): CanvasDocumentState {
  return {
    ...state,
    threadOpen: keep === 'thread' ? state.threadOpen : false,
    addMenuOpen: keep === 'add-menu' ? state.addMenuOpen : false,
    activeGeneratorId: keep === 'generation' ? state.activeGeneratorId : null,
    generationPanelExpanded: keep === 'generation' ? state.generationPanelExpanded : false,
  }
}

function updateGenerator(
  nodes: CanvasNode[],
  id: string,
  updater: (node: GeneratorNode) => GeneratorNode,
): CanvasNode[] {
  return nodes.map((node) => {
    if (node.id !== id || node.type !== 'generator') {
      return node
    }
    return updater(node)
  })
}

function removeGeneratedResults(nodes: CanvasNode[], links: CanvasDocumentState['links']) {
  const ids = new Set(
    nodes
      .filter((node): node is ResultNode => node.type === 'result' && Boolean(node.generated))
      .map((node) => node.id),
  )
  return {
    nodes: nodes.filter((node) => !ids.has(node.id)),
    links: links.filter((link) => !ids.has(link.source) && !ids.has(link.target)),
  }
}

function ensureRunMessage(messages: ThreadMessage[]): ThreadMessage[] {
  const withoutRun = messages.filter((message) => message.kind !== 'run')
  return [...withoutRun, { kind: 'run', runId: 'run' }]
}

function placeGeneratedResult(
  state: CanvasDocumentState,
  run: AgentRunNode,
  stage: StageMetrics,
): CanvasDocumentState {
  const cleaned = removeGeneratedResults(state.nodes, state.links)
  const size = { width: 196, height: 178 }
  const position = findOpenCanvasPosition(cleaned.nodes, size, null, run)
  const { id, sequence } = nextId(state, 'generated')
  const result: ResultNode = {
    id,
    type: 'result',
    domainKind: 'RESOURCE',
    x: position.x,
    y: position.y,
    width: size.width,
    height: size.height,
    title: 'MVP 页面方向 C',
    copy: 'Agent 生成的新结果，避让已有内容并保留来源关系。',
    variant: 'C',
    generated: true,
  }
  const viewport = revealNodeViewportShift(result, state.viewport, stage)
  return markSaving(withToast({
    ...state,
    sequence,
    nodes: [...cleaned.nodes, result],
    links: [
      ...cleaned.links,
      { id: `${run.id}->${result.id}`, source: run.id, target: result.id, role: 'visibility' },
    ],
    selectedIds: [result.id],
    activeGeneratorId: null,
    messages: [
      ...state.messages,
      { kind: 'agent', text: '任务完成：已生成可编辑的 MVP 页面方向，并保留来源关系。' },
    ],
    forceThreadScroll: true,
    viewport,
  }, '任务完成：新结果已避让已有内容并保留来源关系'))
}

export function canvasReducer(state: CanvasDocumentState, action: CanvasAction): CanvasDocumentState {
  switch (action.type) {
    case 'set-view':
      return {
        ...state,
        view: action.view,
        threadOpen: false,
        addMenuOpen: false,
        activeGeneratorId: null,
      }
    case 'set-library-filter':
      return withToast(
        { ...state, libraryFilter: action.filter },
        translate(action.filter === 'all' ? 'canvas.toast.library.all' : 'canvas.toast.library.filterUpdated'),
      )
    case 'set-selected-template':
      return withToast(
        { ...state, selectedTemplate: action.template },
        translate('canvas.toast.templateSelected', { template: action.template }),
      )
    case 'set-idea':
      return { ...state, idea: action.idea }
    case 'set-toast':
      return { ...state, toast: action.toast }
    case 'set-research-open':
      return { ...state, researchOpen: action.open }
    case 'set-research-tab':
      return { ...state, researchTab: action.tab }
    case 'set-help-open':
      return { ...state, helpOpen: action.open }
    case 'set-thread-open': {
      if (!action.open) {
        return { ...state, threadOpen: false }
      }
      return {
        ...closeOverlaysExcept(state, 'none'),
        threadOpen: true,
        addMenuOpen: false,
        activeGeneratorId: null,
      }
    }
    case 'set-add-menu-open': {
      if (!action.open) {
        return { ...state, addMenuOpen: false, addMenuIndex: 0 }
      }
      return {
        ...closeOverlaysExcept(state, 'none'),
        addMenuOpen: true,
        addMenuIndex: state.addMenuIndex,
        threadOpen: false,
        activeGeneratorId: null,
      }
    }
    case 'set-add-menu-index':
      return { ...state, addMenuIndex: action.index }
    case 'set-agent-prompt':
      return { ...state, agentPrompt: action.value }
    case 'set-generation-prompt':
      return { ...state, generationPromptDraft: action.value }
    case 'set-context-mode':
      return { ...state, contextMode: action.mode }
    case 'set-tool': {
      if (state.tool === action.tool) {
        return state
      }
      const next = { ...state, tool: action.tool }
      if (action.silent) {
        return next
      }
      return withToast(
        next,
        translate(action.tool === 'hand' ? 'canvas.toast.tool.hand' : 'canvas.toast.tool.select'),
      )
    }
    case 'set-viewport': {
      const next = {
        x: action.viewport.x,
        y: action.viewport.y,
        scale: clampZoom(action.viewport.scale),
      }
      if (viewportsEqual(state.viewport, next)) {
        return state
      }
      return { ...state, viewport: next }
    }
    case 'set-selection': {
      const next = [...action.ids]
      const same = next.length === state.selectedIds.length && next.every((id, index) => id === state.selectedIds[index])
      if (same) {
        return state
      }
      const activeGenerator = next.length === 1 ? findGenerator(state.nodes, next[0]) : null
      if (activeGenerator) {
        return {
          ...closeOverlaysExcept(state, 'none'),
          selectedIds: next,
          activeGeneratorId: activeGenerator.id,
          generationPromptDraft: activeGenerator.prompt,
          threadOpen: false,
          addMenuOpen: false,
        }
      }
      return {
        ...state,
        selectedIds: next,
        activeGeneratorId: null,
      }
    }
    case 'clear-selection':
      return { ...state, selectedIds: [], activeGeneratorId: null }
    case 'set-save-state':
      return { ...state, saveState: action.saveState }
    case 'move-nodes': {
      const map = new Map(action.updates.map((item) => [item.id, item]))
      let changed = false
      const nodes = state.nodes.map((node) => {
        const update = map.get(node.id)
        if (!update || (update.x === node.x && update.y === node.y)) {
          return node
        }
        changed = true
        return { ...node, x: update.x, y: update.y }
      })
      if (!changed) {
        return state
      }
      return markSaving({
        ...state,
        nodes,
      })
    }
    case 'delete-selection': {
      if (state.selectedIds.length === 0) {
        return state
      }
      const selected = new Set(state.selectedIds)
      return markSaving(withToast({
        ...state,
        nodes: state.nodes.filter((node) => !selected.has(node.id)),
        links: state.links.filter((link) => !selected.has(link.source) && !selected.has(link.target)),
        selectedIds: [],
        activeGeneratorId: selected.has(state.activeGeneratorId ?? '') ? null : state.activeGeneratorId,
      }, translate('canvas.toast.selection.deleted', { count: selected.size })))
    }
    case 'activate-generator': {
      if (!action.id) {
        return { ...state, activeGeneratorId: null }
      }
      const generator = findGenerator(state.nodes, action.id)
      if (!generator) {
        return { ...state, activeGeneratorId: null }
      }
      return {
        ...closeOverlaysExcept(state, 'none'),
        activeGeneratorId: generator.id,
        selectedIds: [generator.id],
        generationPromptDraft: generator.prompt,
        threadOpen: false,
        addMenuOpen: false,
        focusGenerationPromptToken: action.focusPrompt
          ? state.focusGenerationPromptToken + 1
          : state.focusGenerationPromptToken,
      }
    }
    case 'toggle-generation-expanded':
      return { ...state, generationPanelExpanded: !state.generationPanelExpanded }
    case 'set-generation-capability': {
      if (!state.activeGeneratorId) {
        return state
      }
      return markSaving({
        ...state,
        nodes: updateGenerator(state.nodes, state.activeGeneratorId, (node) => ({
          ...node,
          capability: action.capability,
          status: 'draft',
          title: generatorTitle(node.generationMode, 'draft'),
          meta: `${translate(action.capability)} · ${generatorStatusLabel('draft')}`,
        })),
      })
    }
    case 'cycle-parameter': {
      if (!state.activeGeneratorId) {
        return state
      }
      return markSaving({
        ...state,
        nodes: updateGenerator(state.nodes, state.activeGeneratorId, (node) => {
          const profile = GENERATION_PROFILES[node.generationMode]
          const values = profile.groups[action.index]?.values ?? []
          if (values.length === 0) {
            return node
          }
          const nextIndexes = [...node.parameterIndexes]
          nextIndexes[action.index] = ((nextIndexes[action.index] ?? 0) + 1) % values.length
          return {
            ...node,
            parameterIndexes: nextIndexes,
            status: 'draft',
            title: generatorTitle(node.generationMode, 'draft'),
            meta: `${translate(node.capability)} · ${generatorStatusLabel('draft')}`,
          }
        }),
      })
    }
    case 'toggle-reference': {
      if (!state.activeGeneratorId) {
        return state
      }
      return markSaving({
        ...state,
        nodes: updateGenerator(state.nodes, state.activeGeneratorId, (node) => {
          const references = [...node.references]
          references[action.index] = !references[action.index]
          return {
            ...node,
            references,
            status: 'draft',
            title: generatorTitle(node.generationMode, 'draft'),
          }
        }),
      })
    }
    case 'create-generator': {
      const profile = GENERATION_PROFILES[action.mode]
      const preferred = preferredCreatePosition(state.viewport, action.stage, profile.size)
      const position = findOpenCanvasPosition(state.nodes, profile.size, preferred)
      const { id, sequence } = nextId(state, 'generator')
      const node = initializeGeneratorNode({ id, ...position }, action.mode)
      const viewport = revealNodeViewportShift(node, state.viewport, action.stage)
      return markSaving(withToast({
        ...closeOverlaysExcept(state, 'none'),
        sequence,
        nodes: [...state.nodes, node],
        selectedIds: [node.id],
        activeGeneratorId: node.id,
        generationPromptDraft: node.prompt,
        addMenuOpen: false,
        threadOpen: false,
        viewport,
        focusGenerationPromptToken: state.focusGenerationPromptToken + 1,
      }, translate('canvas.toast.generation.created', { label: translate(profile.labelKey) })))
    }
    case 'submit-generation': {
      const node = findGenerator(state.nodes, state.activeGeneratorId)
      const prompt = state.generationPromptDraft.trim()
      if (!node) {
        return state
      }
      if (!prompt) {
        return withToast(state, translate('canvas.toast.generation.promptRequired'))
      }
      const profile = GENERATION_PROFILES[node.generationMode]
      const updated = {
        ...node,
        prompt,
        status: 'generated' as const,
        title: generatorTitle(node.generationMode, 'generated'),
        copy: generatorSummary(prompt),
        meta: getParameterSummary({ ...node, prompt }),
      }
      // Generation updates the node in place and records a message without forcing Thread open.
      return markSaving(withToast({
        ...state,
        nodes: state.nodes.map((item) => (item.id === node.id ? updated : item)),
        messages: [
          ...state.messages,
          {
            kind: 'generation',
            mode: node.generationMode,
            parameters: updated.meta,
            text: translate('canvas.toast.generation.completedMessage', { label: translate(profile.labelKey) }),
          },
        ],
      }, translate('canvas.toast.generation.completed', { label: translate(profile.labelKey) })))
    }
    case 'handle-add-action': {
      if (action.action === 'text' || action.action === 'image' || action.action === 'video') {
        return canvasReducer(state, { type: 'create-generator', mode: action.action, stage: action.stage })
      }
      return withToast(
        { ...state, addMenuOpen: false },
        action.action === 'file'
          ? translate('canvas.toast.add.file')
          : translate('canvas.toast.add.frame'),
      )
    }
    case 'create-text-node': {
      const scale = state.viewport.scale || 0.6
      const { id, sequence } = nextId(state, 'text')
      const node: CanvasNode = {
        id,
        type: 'text',
        domainKind: 'RESOURCE',
        x: Math.round((action.stage.width / 2 - state.viewport.x) / scale - 120),
        y: Math.round((action.stage.height / 2 - state.viewport.y) / scale - 52),
        width: 240,
        height: 104,
        title: '新建文本',
        copy: '在完整产品中可直接编辑此文本。',
        meta: '文本 · 新建',
      }
      return markSaving(withToast({
        ...state,
        sequence,
        nodes: [...state.nodes, node],
        selectedIds: [node.id],
        activeGeneratorId: null,
      }, translate('canvas.toast.text.created')))
    }
    case 'send-agent-message': {
      const text = state.agentPrompt.trim()
      if (!text) {
        return withToast(state, translate('canvas.toast.agent.promptRequired'))
      }
      const run = findRun(state.nodes)
      if (!run) {
        return withToast(state, translate('canvas.toast.agent.runMissing'))
      }
      if (run.status === 'running' || run.status === 'paused') {
        const status = translate(
          run.status === 'running'
            ? 'canvas.agent.run.status.running'
            : 'canvas.agent.run.status.paused',
        )
        return withToast(state, translate('canvas.toast.agent.busy', { status }))
      }
      const cleaned = removeGeneratedResults(state.nodes, state.links)
      const nextRun: AgentRunNode = {
        ...run,
        status: 'running',
        progress: 0,
        title: text.length > 24 ? `${text.slice(0, 24)}…` : text,
      }
      return {
        ...closeOverlaysExcept(state, 'none'),
        nodes: cleaned.nodes.map((node) => (node.id === run.id ? nextRun : node)),
        links: cleaned.links,
        messages: ensureRunMessage([
          ...state.messages,
          { kind: 'user', text },
          { kind: 'agent', text: '我会先梳理上下文，再把执行过程和可编辑结果放回画布。' },
        ]),
        agentPrompt: '',
        threadOpen: true,
        addMenuOpen: false,
        activeGeneratorId: null,
        forceThreadScroll: true,
      }
    }
    case 'pause-agent-run': {
      const run = findRun(state.nodes)
      if (!run || run.status !== 'running') {
        return state
      }
      return {
        ...state,
        nodes: state.nodes.map((node) => (
          node.id === run.id ? { ...run, status: 'paused' as const } : node
        )),
      }
    }
    case 'resume-agent-run': {
      const run = findRun(state.nodes)
      if (!run || run.status !== 'paused') {
        return state
      }
      return {
        ...state,
        nodes: state.nodes.map((node) => (
          node.id === run.id ? { ...run, status: 'running' as const } : node
        )),
      }
    }
    case 'retry-agent-run': {
      const run = findRun(state.nodes)
      if (!run) {
        return state
      }
      const cleaned = removeGeneratedResults(state.nodes, state.links)
      return {
        ...state,
        nodes: cleaned.nodes.map((node) => (
          node.id === run.id
            ? { ...run, status: 'running' as const, progress: 0 }
            : node
        )),
        links: cleaned.links,
        messages: ensureRunMessage(state.messages),
        forceThreadScroll: true,
        threadOpen: true,
        addMenuOpen: false,
        activeGeneratorId: null,
      }
    }
    case 'tick-agent-run': {
      const run = findRun(state.nodes)
      if (!run || run.status !== 'running') {
        return state
      }
      const progress = Math.min(run.total, run.progress + 1)
      if (progress >= run.total) {
        return canvasReducer({
          ...state,
          nodes: state.nodes.map((node) => (
            node.id === run.id ? { ...run, progress, status: 'succeeded' as const } : node
          )),
        }, { type: 'finish-agent-run', stage: action.stage })
      }
      return {
        ...state,
        nodes: state.nodes.map((node) => (
          node.id === run.id ? { ...run, progress } : node
        )),
      }
    }
    case 'finish-agent-run': {
      const run = findRun(state.nodes)
      if (!run) {
        return state
      }
      const succeeded: AgentRunNode = { ...run, status: 'succeeded', progress: run.total }
      return placeGeneratedResult({
        ...state,
        nodes: state.nodes.map((node) => (node.id === run.id ? succeeded : node)),
      }, succeeded, action.stage)
    }
    case 'reset-demo':
      return withToast({
        ...createInitialCanvasState(),
        view: 'editor',
      }, translate('canvas.toast.demo.reset'))
    case 'consume-thread-scroll':
      return { ...state, forceThreadScroll: false }
    case 'open-editor-from-idea': {
      const idea = state.idea.trim()
      if (!idea) {
        return withToast(state, translate('canvas.toast.idea.required'))
      }
      return withToast(
        { ...state, view: 'editor' },
        translate('canvas.toast.idea.created', { template: state.selectedTemplate }),
      )
    }
    case 'mark-generator-draft-from-prompt': {
      if (!state.activeGeneratorId) {
        return state
      }
      const prompt = state.generationPromptDraft
      return markSaving({
        ...state,
        nodes: updateGenerator(state.nodes, state.activeGeneratorId, (node) => ({
          ...node,
          prompt,
          status: 'draft',
          title: generatorTitle(node.generationMode, 'draft'),
          copy: generatorSummary(prompt),
          meta: `${translate(node.capability)} · ${generatorStatusLabel('draft')}`,
        })),
      })
    }
    case 'focus-agent-prompt':
      return {
        ...closeOverlaysExcept(state, 'none'),
        threadOpen: state.messages.length > 0 || state.threadOpen,
        addMenuOpen: false,
        activeGeneratorId: null,
        focusAgentPromptToken: state.focusAgentPromptToken + 1,
      }
    default:
      return state
  }
}

export function getActiveGenerator(state: CanvasDocumentState): GeneratorNode | null {
  return findGenerator(state.nodes, state.activeGeneratorId)
}

export function getContextDescription(
  state: CanvasDocumentState,
  t: typeof translate = translate,
): { count: number; description: string } {
  if (state.contextMode === 'whole') {
    return {
      count: state.nodes.length,
      description: t('canvas.context.whole'),
    }
  }
  if (state.selectedIds.length > 0) {
    return {
      count: state.selectedIds.length,
      description: t('canvas.context.selection', { count: state.selectedIds.length }),
    }
  }
  return {
    count: DEFAULT_CONTEXT_IDS.length,
    description: t('canvas.context.default'),
  }
}
