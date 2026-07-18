import type { ReactNode } from 'react'
import type { DialogueStatus, ToolAttachment } from '@/features/ai/thread-events'

/**
 * Pi-aligned tool rendering extension surface.
 * Features/tools register custom call/result renderers without touching the panel shell.
 */
export interface ToolRenderContext {
  toolName: string
  toolCallId: string
  arguments: string
  text: string
  attachments: ToolAttachment[]
  status?: DialogueStatus
  errorMessage?: string
}

export interface ToolRenderer {
  /** Custom presentation for the tool invocation / arguments phase. */
  renderCall?: (context: ToolRenderContext) => ReactNode
  /** Custom presentation for tool output / artifacts. */
  renderResult?: (context: ToolRenderContext) => ReactNode
}

const registry = new Map<string, ToolRenderer>()

export function registerToolRenderer(toolName: string, renderer: ToolRenderer): void {
  const key = toolName.trim()
  if (!key) {
    return
  }
  registry.set(key, renderer)
}

export function unregisterToolRenderer(toolName: string): void {
  registry.delete(toolName.trim())
}

export function getToolRenderer(toolName: string): ToolRenderer | undefined {
  return registry.get(toolName.trim())
}

export function clearToolRenderers(): void {
  registry.clear()
}
