import { useCallback, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import type { ChatSpaceDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import type { TreeRow } from './resource-tree'
import { bindBeside, bindPane, canOpenDirectly, newDraft, reconcileBindings, sameTarget } from './pane-bindings'
import { isPaneTarget, type PaneRendererProps, type PaneRenderers, type PaneTarget } from './pane-target'
import { closePane, paneIds, usePaneState, type PaneState, type Rect } from './panes'

export type OpenTarget = (target: PaneTarget, anchor?: HTMLElement | null) => void

type Bindings = PaneState<PaneTarget>
function targetKey(target: PaneTarget) {
  switch (target.kind) {
    case 'draft': return `draft:${target.draftId}`
    case 'thread': return `thread:${target.threadId}`
    case 'canvas': return `canvas:${target.canvasId}:${target.viewSlot ?? ''}`
    case 'vscode': return `vscode:${target.environmentId}:${target.viewId}`
  }
}

export function useWorkspacePanes({ userId, space, rows, renderers, closeView, openSpaceSettings, report }: {
  userId: string
  space: ChatSpaceDTO
  rows: ReadonlyMap<string, TreeRow>
  renderers: Partial<PaneRenderers>
  closeView: (viewId: string) => Promise<boolean>
  openSpaceSettings: () => void
  report: (message: string) => void
}) {
  const { t } = useI18n()
  const [stored, store] = usePaneState(userId, space.id, isPaneTarget)
  const [observedRows, observe] = useState<ReadonlyMap<string, TreeRow>>()
  const state = reconcileBindings(stored, target => observedRows === rows || (
    target.kind === 'thread' ? rows.get(target.threadId)?.kind === 'thread'
      : target.kind !== 'canvas' || rows.get(target.canvasId)?.kind === 'canvas'
  ))
  if (observedRows !== rows) observe(rows)
  if (state !== stored) store(state)
  const [fullscreen, setFullscreen] = useState(false)
  const [busy, setBusy] = useState(false)
  const locking = useRef(false)
  const [unsent, setUnsent] = useState<Readonly<Record<string, string>>>({})
  const [pick, setPick] = useState<{ target: PaneTarget; anchor?: HTMLElement | null }>()
  const [confirm, setConfirm] = useState<{ run: () => void }>()
  const bounds = useRef<Rect>({ x: 0, y: 0, width: 0, height: 0 })
  const onSizeChange = useCallback((box: Rect) => { bounds.current = box }, [])
  const setState = useCallback<Dispatch<SetStateAction<Bindings>>>(action => {
    if (!locking.current) store(previous => reconcileBindings(typeof action === 'function' ? action(previous) : action, () => true))
  }, [store])
  const hasUnsent = (id: string) => {
    const current = state.bindings[id]
    return current !== undefined && unsent[id] === targetKey(current)
  }

  function transition(id: string, update: (previous: Bindings) => Bindings, next?: PaneTarget, replacementAlreadyAuthorized = false) {
    if (locking.current) return
    const current = state.bindings[id]
    if (next && sameTarget(current, next)) {
      setState(previous => ({ ...previous, focusedPaneId: id }))
      return
    }
    const run = () => {
      if (current?.kind !== 'vscode') { setState(update); return }
      locking.current = true
      setBusy(true)
      void closeView(current.viewId).then(ok => {
        if (ok) store(previous => sameTarget(previous.bindings[id], current)
          ? reconcileBindings(update(previous), () => true) : previous)
      }).catch(() => report(t('shared.requestFailed'))).finally(() => {
        locking.current = false
        setBusy(false)
      })
    }
    if (!replacementAlreadyAuthorized && hasUnsent(id)) setConfirm({ run })
    else run()
  }

  const open: OpenTarget = (target, anchor) => {
    if (locking.current) return
    if (canOpenDirectly(state, state.focusedPaneId, hasUnsent)) {
      setState(previous => bindPane(previous, previous.focusedPaneId, target))
    } else setPick({ target, anchor })
  }

  function describePane(id: string, target: PaneTarget | undefined) {
    if (!target) return { title: '', content: null }
    const key = targetKey(target)
    const props: Omit<PaneRendererProps<PaneTarget>, 'target'> = {
      space,
      active: id === state.focusedPaneId,
      // First sends and confirmed branch/fork actions have already authorized this replacement.
      replace: next => transition(id, previous => bindPane(previous, id, next), next, true),
      open,
      openBeside: next => {
        if (locking.current) return
        const placed = bindBeside(state, id, next, hasUnsent, bounds.current, fullscreen)
        if (placed) setState(placed)
        else report(t('pane.closeBeforeOpening'))
      },
      openSpaceSettings,
      onUnsentChange: value => setUnsent(previous => {
        if (value) return previous[id] === key ? previous : { ...previous, [id]: key }
        if (previous[id] !== key) return previous
        const next = { ...previous }
        delete next[id]
        return next
      }),
    }
    switch (target.kind) {
      case 'thread': return { title: rows.get(target.threadId)?.name ?? t('pane.thread'), content: renderers.thread && <renderers.thread key={key} {...props} target={target} /> }
      case 'canvas': return { title: rows.get(target.canvasId)?.name ?? t('pane.canvas'), content: renderers.canvas && <renderers.canvas key={key} {...props} target={target} /> }
      case 'vscode': return { title: 'VS Code', content: renderers.vscode && <renderers.vscode key={key} {...props} target={target} /> }
      case 'draft': return { title: t('pane.newSession'), content: renderers.draft && <renderers.draft key={key} {...props} target={target} /> }
    }
  }

  return {
    open,
    busy,
    hasUnsentContent: paneIds(state.layout).some(hasUnsent),
    findOpenView: (environmentId: string) => Object.values(state.bindings)
      .find((target): target is Extract<PaneTarget, { kind: 'vscode' }> =>
        target?.kind === 'vscode' && target.environmentId === environmentId)?.viewId,
    removeView: (viewId: string) => store(previous => {
      const id = paneIds(previous.layout).find(id => {
        const target = previous.bindings[id]
        return target?.kind === 'vscode' && target.viewId === viewId
      })
      if (!id) return previous
      const closed = closePane(previous, id)
      return closed === previous ? bindPane(previous, id, newDraft()) : closed
    }),
    layoutProps: {
      state, setState, fullscreen, onFullscreenChange: setFullscreen, onSizeChange, describePane,
      onClosePane: (id: string) => transition(id, previous => closePane(previous, id)),
      picker: pick && {
        returnFocusTo: pick.anchor,
        onCancel: () => setPick(undefined),
        onSelect: (id: string) => {
          setPick(undefined)
          transition(id, previous => bindPane(previous, id, pick.target), pick.target)
        },
      },
    },
    confirmation: <ConfirmActionModal pending={false} onClose={() => setConfirm(undefined)} modal={confirm ? {
      title: t('pane.discardUnsent'), description: t('pane.discardUnsentPrompt'), tone: 'danger',
      onConfirm: () => { setConfirm(undefined); confirm.run() },
    } : null} />,
  }
}
