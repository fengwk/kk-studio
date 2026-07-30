import type { ReactNode } from 'react'
import type { DialogueStatus, ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'

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
  renderCall?: (context: ToolRenderContext) => ReactNode
  renderResult?: (context: ToolRenderContext) => ReactNode
}

const registry = new Map<string, ToolRenderer>()

export function registerToolRenderer(toolName: string, renderer: ToolRenderer): void {
  const key = toolName.trim()
  if (key) {
    registry.set(key, renderer)
  }
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
