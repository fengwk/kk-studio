import type { XYPosition } from '@xyflow/react'

export const TEXT_NODE_WIDTH = 272
export const TEXT_NODE_HEIGHT = 112

export interface LocalTextNode {
  id: string
  name: string
  text: string
  position: XYPosition
}

const sampleNotes = [
  ['整理素材', '在画布里整理需求、参考素材和提示词。'],
  ['并排对照', '拆出一个窗格，再打开对话，边看边改。'],
  ['记录结论', '把确认的方案与下一步写成文字节点，拖动卡片标题重新排列。'],
] as const

function createLocalCanvas(sample: boolean) {
  let sequence = 0
  const makeNode = (name: string, text: string, position: XYPosition): LocalTextNode => (
    { id: `text-${++sequence}`, name, text, position }
  )
  let nodes = sample ? sampleNotes.map(([name, text], index) => (
    makeNode(name, text, { x: index === 1 ? 20 : 0, y: index * (TEXT_NODE_HEIGHT + 16) })
  )) : []
  const listeners = new Set<() => void>()

  function publish(next: LocalTextNode[]) {
    nodes = next
    listeners.forEach((listener) => listener())
  }

  return {
    getSnapshot: () => nodes,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    addText(origin: XYPosition) {
      const position = { ...origin }
      while (nodes.some((node) => (
        Math.abs(node.position.x - position.x) < TEXT_NODE_WIDTH + 16
        && Math.abs(node.position.y - position.y) < TEXT_NODE_HEIGHT + 16
      ))) {
        position.y += TEXT_NODE_HEIGHT + 16
      }
      const node = makeNode(`文字 ${sequence + 1}`, '', position)
      publish([...nodes, node])
      return node
    },
    editText(id: string, text: string) {
      publish(nodes.map((node) => node.id === id ? { ...node, text } : node))
    },
    moveNodes(positions: Map<string, XYPosition>) {
      publish(nodes.map((node) => {
        const position = positions.get(node.id)
        return position ? { ...node, position } : node
      }))
    },
  }
}

// 只在当前页面存活；同一个资源 ID 在所有 Pane 共享内容，不共享视角。
const canvases = new Map<string, ReturnType<typeof createLocalCanvas>>()
export function getLocalCanvas(id: string, sample: boolean) {
  let canvas = canvases.get(id)
  if (!canvas) {
    canvas = createLocalCanvas(sample)
    canvases.set(id, canvas)
  }
  return canvas
}
