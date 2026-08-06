/**
 * Canvas presentation model used by the local demo UI.
 *
 * This is not the React Flow contract and not the wire DTO.
 * Durable Studio vocabulary is `StudioNodeKind` + backend `studio` module.
 * The current demo projection assigns `domainKind` at node construction sites.
 */

/** Backend CanvasNodeKind. Presentation nodes always carry this. */
export type StudioNodeKind = 'RESOURCE' | 'FUNCTION' | 'GROUP'

export type GenerationMode = 'text' | 'image' | 'video'

type GeneratorStatus = 'draft' | 'generated'

type AgentRunStatus = 'running' | 'paused' | 'succeeded'

/** UI renderer key — finer than StudioNodeKind. */
export type CanvasNodeType =
  | 'frame'
  | 'web'
  | 'image'
  | 'file'
  | 'text'
  | 'generator'
  | 'run'
  | 'matrix'
  | 'result'

export type LibraryFilter = 'all' | 'mine' | 'collab'

export type AgentContextMode = 'selection' | 'whole'

export type CanvasTool = 'select' | 'hand'

export type CanvasView = 'library' | 'editor'

export type ResearchTab = 'research' | 'architecture' | 'roadmap'

export type AddMenuAction = GenerationMode | 'file' | 'frame'

type ResultVariant = 'A' | 'B' | 'C'

export interface CanvasPoint {
  x: number
  y: number
}

export interface CanvasSize {
  width: number
  height: number
}

export interface CanvasViewport {
  x: number
  y: number
  scale: number
}

export interface CanvasRect extends CanvasPoint, CanvasSize {}

interface ParameterGroup {
  key: string
  values: string[]
}

export interface GenerationProfile {
  labelKey: string
  icon: string
  prompt: string
  cost: number
  size: CanvasSize
  capabilities: string[]
  groups: ParameterGroup[]
}

interface CanvasNodeBase extends CanvasRect {
  id: string
  /** UI renderer / presentation subtype. */
  type: CanvasNodeType
  /** Durable Studio kind (RESOURCE | FUNCTION | GROUP). */
  domainKind: StudioNodeKind
  title: string
}

export interface FrameNode extends CanvasNodeBase {
  type: 'frame'
  subtitle: string
}

export interface ContentNode extends CanvasNodeBase {
  type: 'web' | 'image' | 'file' | 'text' | 'matrix'
  copy: string
  meta: string
}

export interface GeneratorNode extends CanvasNodeBase {
  type: 'generator'
  generationMode: GenerationMode
  prompt: string
  parameterIndexes: number[]
  references: boolean[]
  capability: string
  status: GeneratorStatus
  copy: string
  meta: string
}

export interface AgentRunNode extends CanvasNodeBase {
  type: 'run'
  status: AgentRunStatus
  progress: number
  total: number
}

export interface ResultNode extends CanvasNodeBase {
  type: 'result'
  copy: string
  variant: ResultVariant
  generated?: boolean
}

export type CanvasNode = FrameNode | ContentNode | GeneratorNode | AgentRunNode | ResultNode

/**
 * Presentation visibility edge.
 * Maps to backend CanvasLink. Does NOT represent ResourceReference dependency.
 */
export interface CanvasLink {
  id: string
  source: string
  target: string
  /** Always visibility in the demo; ResourceReference is a separate future model. */
  role: 'visibility'
}

export type ThreadMessage =
  | { kind: 'user'; text: string }
  | { kind: 'agent'; text: string }
  | { kind: 'run'; runId: string }
  | { kind: 'generation'; mode: GenerationMode; parameters: string; text: string }

export interface CanvasDocumentState {
  nodes: CanvasNode[]
  links: CanvasLink[]
  selectedIds: string[]
  viewport: CanvasViewport
  contextMode: AgentContextMode
  tool: CanvasTool
  messages: ThreadMessage[]
  activeGeneratorId: string | null
  generationPanelExpanded: boolean
  sequence: number
  saveState: 'saved' | 'saving'
  toast: string | null
  libraryFilter: LibraryFilter
  selectedTemplate: string
  idea: string
  view: CanvasView
  researchOpen: boolean
  researchTab: ResearchTab
  helpOpen: boolean
  threadOpen: boolean
  addMenuOpen: boolean
  addMenuIndex: number
  agentPrompt: string
  generationPromptDraft: string
  forceThreadScroll: boolean
  focusAgentPromptToken: number
  focusGenerationPromptToken: number
}

export interface StageMetrics {
  width: number
  height: number
  dockTop: number
}
