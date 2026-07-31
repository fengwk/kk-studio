import type { ComponentType, ReactNode } from 'react'

export type Disposable = () => void

export type WorkbenchSlotName =
  | 'header'
  | 'session-sidebar'
  | 'runtime-header'
  | 'widget'
  | 'transcript'
  | 'composer'
  | 'inspector'
  | 'status'

export interface ExtensionComponentProps {
  children?: ReactNode
}

interface Contribution {
  id: string
  priority?: number
}

export interface PageContribution extends Contribution {
  path: string
  component: ComponentType<ExtensionComponentProps>
}

export interface NavigationContribution extends Contribution {
  label: string
  path: string
}

export interface PanelContribution extends Contribution {
  slot: WorkbenchSlotName
  component: ComponentType<ExtensionComponentProps>
}

export interface WidgetContribution extends Contribution {
  slot: WorkbenchSlotName
  component: ComponentType<ExtensionComponentProps>
}

export interface InspectorContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

export interface CommandContribution extends Contribution {
  title: string
  run: () => void | Promise<void>
}

export interface StatusContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

export interface DialogContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

export interface OverlayContribution extends Contribution {
  component: ComponentType<ExtensionComponentProps>
}

/**
 * Extensions are trusted, compile-time React modules. The host never downloads
 * or evaluates third-party JavaScript at runtime.
 */
export interface TrustedReactExtension {
  id: string
  pages?: PageContribution[]
  navigation?: NavigationContribution[]
  panels?: PanelContribution[]
  widgets?: WidgetContribution[]
  inspectors?: InspectorContribution[]
  commands?: CommandContribution[]
  statuses?: StatusContribution[]
  dialogs?: DialogContribution[]
  overlays?: OverlayContribution[]
}
