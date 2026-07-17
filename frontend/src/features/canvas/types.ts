/** Domain types for the Canvas feature demo model (not React Flow contracts). */

export type GenerationMode = 'text' | 'image' | 'video'

export type GeneratorStatus = 'draft' | 'generated'

export type AgentRunStatus = 'running' | 'paused' | 'succeeded'

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

export type ResultVariant = 'A' | 'B' | 'C'

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

export interface ParameterGroup {
  key: string
  values: string[]
}

export interface GenerationProfile {
  label: string
  icon: string
  prompt: string
  cost: string
  size: CanvasSize
  capabilities: string[]
  groups: ParameterGroup[]
}

export interface CanvasNodeBase extends CanvasRect {
  id: string
  type: CanvasNodeType
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

export interface CanvasLink {
  id: string
  source: string
  target: string
}

export type ThreadMessage =
  | { kind: 'user'; text: string }
  | { kind: 'agent'; text: string }
  | { kind: 'run'; runId: string }
  | { kind: 'generation'; mode: GenerationMode; parameters: string; text: string }

export interface LibraryCard {
  id: string
  title: string
  owner: Exclude<LibraryFilter, 'all'>
  objectsLabel: string
  editedLabel: string
  footerLeft: string
  footerRight: string
  preview: 'research' | 'visual' | 'technical' | 'story'
  featured?: boolean
  muted?: boolean
  runState?: 'completed' | 'paused' | 'removed'
  runStateLabel?: string
  previewBadge?: string
}

export interface TemplateCard {
  id: string
  name: string
  description: string
  icon: string
  iconTone: 'blank' | 'research' | 'visual' | 'story' | 'tech'
}

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
