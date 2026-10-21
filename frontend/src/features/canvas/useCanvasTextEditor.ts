import { useCallback } from 'react'
import { currentCanvasSnapshot, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import type { ResourceNode } from '@/features/canvas/domain'
import type { CanvasLocalState } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

interface TextEditorInput {
  scope: CanvasScope
  snapshot: CanvasSnapshotDTO | undefined
  drafts: CanvasLocalState['drafts']
  textEditor: CanvasLocalState['textEditor']
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<CanvasSnapshotDTO>
  suggestNodeAlias: (preferred: string) => string
  reserveNodeAlias: (preferred: string) => string
  releaseNodeAlias: (alias: string) => void
  nextTransform: () => CanvasTransformDTO
}

/**
 * 文本节点编辑器：编辑既有节点时每次输入都写入带 generation 的文本草稿，保存以草稿 generation 作为 ACK；新建节点只在保存时预留别名并创建。
 */
export function useCanvasTextEditor({
  scope,
  snapshot,
  drafts,
  textEditor,
  executeCommands,
  suggestNodeAlias,
  reserveNodeAlias,
  releaseNodeAlias,
  nextTransform,
}: TextEditorInput) {
  const { setState, draftsRef, targetDraftsRef } = scope

  const editTextNode = useCallback((node: ResourceNode) => {
    const resource = node.resources[0]
    if (!resource || resource.kind !== 'TEXT') {
      return
    }
    setState((current) => ({
      ...current,
      textEditor: {
        mode: 'edit',
        nodeId: node.id,
        name: node.name,
        markdown: resource.textContent ?? '',
      },
    }))
  }, [setState])

  const createTextNode = useCallback(() => {
    setState((current) => ({
      ...current,
      textEditor: {
        mode: 'create',
        nodeId: null,
        name: suggestNodeAlias('文本'),
        markdown: '# 新文本\n\n在这里编写 Markdown。',
      },
    }))
  }, [setState, suggestNodeAlias])

  const setTextEditorDraft = useCallback((patch: { name?: string; markdown?: string }) => {
    setState((current) => {
      if (!current.textEditor) {
        return current
      }
      const updatedEditor = { ...current.textEditor, ...patch }
      if (updatedEditor.mode !== 'edit') {
        return { ...current, textEditor: updatedEditor }
      }
      const nodeId = updatedEditor.nodeId
      const existingDraft = draftsRef.current[nodeId] ?? { generation: 0, updatedAt: Date.now() }
      const nextDraft: CanvasNodeDraft = {
        ...existingDraft,
        generation: existingDraft.generation + 1,
        updatedAt: Date.now(),
        text: {
          name: updatedEditor.name,
          markdown: updatedEditor.markdown,
        },
      }
      draftsRef.current = {
        ...draftsRef.current,
        [nodeId]: nextDraft,
      }
      targetDraftsRef.current = draftsRef.current
      return {
        ...current,
        textEditor: updatedEditor,
        drafts: draftsRef.current,
      }
    })
  }, [draftsRef, setState, targetDraftsRef])

  const closeTextEditor = useCallback(() => {
    setState((current) => ({ ...current, textEditor: null }))
  }, [setState])

  const saveTextEditor = useCallback(() => {
    const editor = textEditor
    if (!editor || !editor.name.trim() || !editor.markdown.trim()) {
      return
    }
    const closeEditor = () => setState((current) => ({ ...current, textEditor: null }))
    if (editor.mode === 'edit') {
      const node = currentCanvasSnapshot(scope, snapshot)?.nodes.find((item) => item.id === editor.nodeId)
      if (!node) {
        return
      }
      const commands: CanvasCommandDTO[] = [
        {
          type: 'SET_NODE_RESOURCES',
          nodeId: editor.nodeId,
          expectedResourceIds: node.resources.map((r) => r.id),
          resources: [{ kind: 'TEXT', name: 'text', textContent: editor.markdown }],
        },
      ]
      if (node.name !== editor.name.trim()) {
        commands.push({
          type: 'RENAME_NODE',
          nodeId: editor.nodeId,
          expectedName: node.name,
          name: editor.name.trim(),
        })
      }
      const currentDraft = drafts[editor.nodeId]
      const ack: CanvasDraftAck[] = currentDraft
        ? [{ nodeId: editor.nodeId, field: 'text', generation: currentDraft.generation }]
        : []
      void executeCommands(commands, ack).then(closeEditor).catch(() => undefined)
      return
    }

    const alias = reserveNodeAlias(editor.name)
    void executeCommands([{
      type: 'CREATE_NODE',
      nodeId: crypto.randomUUID() as UUIDString,
      name: alias,
      transform: nextTransform(),
      resources: [{ kind: 'TEXT', name: 'text', textContent: editor.markdown }],
    }]).then(closeEditor).catch(() => undefined).finally(() => releaseNodeAlias(alias))
  }, [drafts, executeCommands, nextTransform, releaseNodeAlias, reserveNodeAlias, scope, setState, snapshot, textEditor])

  return { editTextNode, createTextNode, setTextEditorDraft, closeTextEditor, saveTextEditor }
}
