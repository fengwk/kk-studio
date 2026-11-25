import type { TreeDrop } from './resource-tree'

const HOLD_MS = 380
const MOVE_TOLERANCE = 8
const SCROLL_EDGE = 40

type DropActions = {
  resolve: (sourceId: string, targetId: string, relativeY: number) => TreeDrop | undefined
  move: (sourceId: string, drop: TreeDrop) => void
}

type Point = { clientX: number; clientY: number }

/** Bind native mouse dragging and a touch hold that does not intercept ordinary scrolling. */
export function bindTreeDrag(tree: HTMLElement, { resolve, move }: DropActions): () => void {
  const listeners = new AbortController()
  let source: HTMLElement | undefined
  let marker: HTMLElement | undefined
  let hold: ReturnType<typeof setTimeout> | undefined
  let touch: { id: number; start: Point } | undefined
  let frame: number | undefined
  let point: Point | undefined

  const rowAt = (target: EventTarget | null) => {
    const row = target instanceof Element ? target.closest<HTMLElement>('[data-tree-row]') : null
    return row && tree.contains(row) ? row : undefined
  }
  const movableRow = (target: EventTarget | null) => {
    if (!(target instanceof Element) || target.closest('button, input, a, [data-tree-action]')) return
    const row = rowAt(target)
    return row?.hasAttribute('data-tree-movable') ? row : undefined
  }
  const sourceId = () => source?.isConnected && source.hasAttribute('data-tree-movable') ? source.dataset.treeRow : undefined

  function mark(row?: HTMLElement, position?: TreeDrop['position']) {
    if (marker && marker !== row) delete marker.dataset.treeDrop
    marker = row
    if (marker && position) marker.dataset.treeDrop = position
  }

  function findDrop({ clientX, clientY }: Point) {
    const row = rowAt(document.elementFromPoint(clientX, clientY))
    const id = sourceId(), targetId = row?.dataset.treeRow
    const rect = row?.getBoundingClientRect()
    const drop = id && targetId && rect?.height ? resolve(id, targetId, (clientY - rect.top) / rect.height) : undefined
    mark(drop ? row : undefined, drop?.position)
    return drop
  }

  function reset() {
    clearTimeout(hold)
    if (frame !== undefined) cancelAnimationFrame(frame)
    if (source) delete source.dataset.treeDragging
    mark()
    source = touch = frame = point = hold = undefined
  }

  function commit(at: Point) {
    const id = sourceId(), drop = findDrop(at)
    reset()
    if (id && drop) move(id, drop)
  }

  function scroll() {
    frame = undefined
    if (!point || !sourceId()) return
    const { top, bottom, height, left, right } = tree.getBoundingClientRect()
    const { clientX, clientY } = point
    if (clientX < left || clientX > right || clientY < top || clientY > bottom) return
    const edge = Math.min(SCROLL_EDGE, height / 3)
    const delta = clientY < top + edge ? clientY - (top + edge) : Math.max(0, clientY - (bottom - edge))
    if (!delta) return
    const before = tree.scrollTop
    tree.scrollTop += delta / 3
    if (tree.scrollTop !== before) {
      findDrop(point)
      frame = requestAnimationFrame(scroll)
    }
  }

  function track(at: Point) {
    point = { clientX: at.clientX, clientY: at.clientY }
    const drop = findDrop(point)
    frame ??= requestAnimationFrame(scroll)
    return drop
  }

  const options = { signal: listeners.signal }
  tree.addEventListener('dragstart', event => {
    const row = movableRow(event.target)
    if (!row || touch || !event.dataTransfer || !row.dataset.treeRow) {
      if (rowAt(event.target)) event.preventDefault()
      return
    }
    source = row
    row.dataset.treeDragging = ''
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', row.dataset.treeRow)
  }, options)
  tree.addEventListener('dragover', event => {
    if (!source || touch || !track(event)) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  }, options)
  tree.addEventListener('dragleave', event => {
    if (event.relatedTarget instanceof Node && tree.contains(event.relatedTarget)) return
    point = undefined
    mark()
  }, options)
  tree.addEventListener('drop', event => {
    if (!source || touch) return
    event.preventDefault()
    commit(event)
  }, options)
  tree.addEventListener('dragend', reset, options)

  tree.addEventListener('touchstart', event => {
    if (event.touches.length !== 1) { reset(); return }
    const row = movableRow(event.target), first = event.touches[0]
    if (!row || !first || source) return
    touch = { id: first.identifier, start: { clientX: first.clientX, clientY: first.clientY } }
    hold = setTimeout(() => {
      if (!tree.contains(row) || !row.hasAttribute('data-tree-movable')) { reset(); return }
      source = row
      row.dataset.treeDragging = ''
    }, HOLD_MS)
  }, { ...options, passive: true })
  tree.addEventListener('touchmove', event => {
    if (!touch) return
    const current = Array.from(event.touches).find(item => item.identifier === touch?.id)
    if (!current || event.touches.length !== 1) { reset(); return }
    if (!source) {
      if (Math.hypot(current.clientX - touch.start.clientX, current.clientY - touch.start.clientY) > MOVE_TOLERANCE) reset()
      return
    }
    event.preventDefault()
    track(current)
  }, { ...options, passive: false })
  tree.addEventListener('touchend', event => {
    if (!touch) return
    const current = Array.from(event.changedTouches).find(item => item.identifier === touch?.id)
    if (!current) return
    if (!source) { reset(); return }
    event.preventDefault()
    commit(current)
  }, { ...options, passive: false })
  tree.addEventListener('touchcancel', reset, options)
  tree.addEventListener('contextmenu', event => {
    if (!touch) return
    event.preventDefault()
    event.stopImmediatePropagation()
  }, { ...options, capture: true })

  return () => {
    listeners.abort()
    reset()
  }
}
