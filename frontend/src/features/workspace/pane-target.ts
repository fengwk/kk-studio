import type { ComponentType } from 'react'
import type { ChatSpaceDTO } from '@/shared/api/generated/contracts'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { hasExactKeys, isRecord } from '@/shared/lib/value'
import { MAX_PANES } from './panes/model'

/**
 * What one workspace pane shows. The workspace stores it per pane ID in the browser.
 *
 * - `draft`: the "new session" composer. `draftId` is the Idempotency-Key of the first message
 *   and keys the unsent text; `folderId` is set when the draft was started from a directory,
 *   otherwise the first top-level folder is used when sending.
 * - `thread`: a public Thread of this space.
 * - `canvas`: a Canvas of this space. Omit `viewSlot` for the primary view; additional views use a reusable Canvas-local slot.
 * - `vscode`: one VS Code workbench view of an environment.
 */
export type PaneTarget =
  | { kind: 'draft'; draftId: string; folderId?: string }
  | { kind: 'thread'; threadId: string }
  | { kind: 'canvas'; canvasId: string; viewSlot?: number }
  | { kind: 'vscode'; environmentId: string; viewId: string }

export type PaneTargetKind = PaneTarget['kind']

export function isPaneTarget(value: unknown): value is PaneTarget {
  if (!isRecord(value)) return false
  switch (value.kind) {
    case 'draft':
      return isCanonicalUuid(value.draftId) && (value.folderId === undefined
        ? hasExactKeys(value, ['kind', 'draftId'])
        : hasExactKeys(value, ['kind', 'draftId', 'folderId']) && isCanonicalUuid(value.folderId))
    case 'thread':
      return hasExactKeys(value, ['kind', 'threadId']) && isCanonicalUuid(value.threadId)
    case 'canvas':
      return isCanonicalUuid(value.canvasId) && (value.viewSlot === undefined
        ? hasExactKeys(value, ['kind', 'canvasId'])
        : hasExactKeys(value, ['kind', 'canvasId', 'viewSlot'])
          && typeof value.viewSlot === 'number' && Number.isInteger(value.viewSlot)
          && value.viewSlot >= 1 && value.viewSlot < MAX_PANES)
    case 'vscode':
      return hasExactKeys(value, ['kind', 'environmentId', 'viewId'])
        && isCanonicalUuid(value.environmentId) && isCanonicalUuid(value.viewId)
    default:
      return false
  }
}

/** What the workspace gives the component that renders one pane target. */
export type PaneRendererProps<T extends PaneTarget> = {
  space: ChatSpaceDTO
  target: T
  /** The pane is the focused pane. */
  active: boolean
  /** Replace this pane's target in place: draft → thread after the first message, branch or fork → the new thread. */
  replace: (next: PaneTarget) => void
  /** Open another target through the workspace open flow (directly, or after the pane picker). */
  open: (next: PaneTarget, anchor?: HTMLElement | null) => void
  /** Open another target without replacing this pane: in an empty pane, otherwise in a new pane split from this one. */
  openBeside: (next: PaneTarget) => void
  /** Report whether the pane holds unsent input; the workspace confirms before replacing or closing it. */
  onUnsentChange: (unsent: boolean) => void
  /** Open the space's default configuration (default Agent, environment and permission). */
  openSpaceSettings: () => void
}

/** One renderer per target kind. The application layer composes them; the workspace does not import renderers. */
export type PaneRenderers = {
  [K in PaneTargetKind]: ComponentType<PaneRendererProps<Extract<PaneTarget, { kind: K }>>>
}
