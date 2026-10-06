import type { ComponentType, ReactNode } from 'react'

export type Disposable = () => void

export interface ExtensionComponentProps {
  children?: ReactNode
}

interface Contribution {
  id: string
  priority?: number
}

export interface PageNavItem {
  label: string
  labelKey?: string
  order?: number
}

export type PageWorkspacePredicate =
  | boolean
  | ((params: Record<string, string | undefined>) => boolean)

export interface PageContribution extends Contribution {
  path: string
  component: ComponentType<ExtensionComponentProps>
  navGroup?: string
  navItem?: PageNavItem
  workspace?: PageWorkspacePredicate
}

export interface DialogContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

export interface OverlayContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

export type ToolRendererPhase = 'call' | 'result'
export type ToolRendererStatus = 'streaming' | 'done' | 'error'
export type ToolRendererAttachmentType = 'image' | 'audio' | 'video' | 'file'

/** Tool renderer contribution 可消费的稳定、与 Harness DTO 解耦的资源投影。 */
export interface ToolRendererAttachment {
  type: ToolRendererAttachmentType
  name: string
  mime: string
  data: string
  preview?: string
  size?: number | null
  sha256?: string | null
  downloadHref?: string
}

/** 有序 Tool 结果内容：Text 忠实展示、JSON 格式化、Resource 按权威 MIME 渲染。 */
export type ToolRendererContent =
  | { type: 'text'; text: string }
  | { type: 'json'; value: unknown }
  | { type: 'resource'; attachment: ToolRendererAttachment }

/** Tool renderer contribution 的只读展示模型；审批与状态机操作仍由宿主块负责。 */
export interface ToolRendererMessage {
  rendererKey: string
  phase: ToolRendererPhase
  contents: ToolRendererContent[]
  toolCallId: string
  toolName: string
  arguments: string
  status?: ToolRendererStatus
  errorMessage?: string
}

export interface ToolRendererProps {
  message: ToolRendererMessage
  /** Tool 宿主卡片是否处于展开态；renderer 应在收起态遵守紧凑预览。 */
  expanded?: boolean
}

/**
 * id 必须与后端 ToolDescriptor 冻结的 rendererKey 精确相同；未注册时由宿主回退到默认 renderer。
 * 展开/收起由宿主按有序内容统一裁决，contribution 不再声明可展开性。
 */
export interface ToolRendererContribution extends Contribution {
  component: ComponentType<ToolRendererProps>
}

/**
 * Extension 是受信任的、编译期 React 模块。宿主在运行时绝不会下载
 * 或评估第三方 JavaScript。
 */
export interface TrustedReactExtension {
  id: string
  pages?: PageContribution[]
  dialogs?: DialogContribution[]
  overlays?: OverlayContribution[]
  toolRenderers?: ToolRendererContribution[]
}
