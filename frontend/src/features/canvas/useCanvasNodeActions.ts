import { useCallback } from 'react'
import { currentCanvasSnapshot, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import { projectCanvasSnapshot, type ResourceNode } from '@/features/canvas/domain'
import { configToFunctionArgs, createDefaultFunctionConfig } from '@/features/canvas/generation'
import type { AddMenuAction, CanvasLocalState } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasFunctionDefinitionDTO,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

interface NodeActionsInput {
  scope: CanvasScope
  snapshot: CanvasSnapshotDTO | undefined
  models: CanvasFunctionDefinitionDTO[] | undefined
  state: Pick<CanvasLocalState, 'selectedIds' | 'positionDrafts' | 'drafts'>
  executeCommands: (commands: CanvasCommandDTO[], ack?: CanvasDraftAck[]) => Promise<CanvasSnapshotDTO>
  flushFunctionConfig: (nodeId: string) => Promise<unknown>
  setToast: (toast: string) => void
  suggestNodeAlias: (preferred: string) => string
  reserveNodeAlias: (preferred: string) => string
  releaseNodeAlias: (alias: string) => void
  nextTransform: () => CanvasTransformDTO
  dismissDraft: (nodeId: string) => void
  createTextNode: () => void
}

/**
 * 节点与分组的结构性编辑：创建函数节点、重命名、分组/取消分组、删除选区与节点，以及冲突草稿的「另存为新节点」与重试。
 *
 * 每个操作都携带当前看到的期望值（名称、资源、函数、成员），由服务端 CAS 拒绝陈旧意图；失败已由命令队列转成提示或冲突草稿，这里不再重复处理。
 */
export function useCanvasNodeActions({
  scope,
  snapshot,
  models,
  state,
  executeCommands,
  flushFunctionConfig,
  setToast,
  suggestNodeAlias,
  reserveNodeAlias,
  releaseNodeAlias,
  nextTransform,
  dismissDraft,
  createTextNode,
}: NodeActionsInput) {
  const { setState } = scope
  const { selectedIds, positionDrafts, drafts } = state

  const renameNode = useCallback((nodeId: string, name: string, expectedName: string) => {
    const normalized = name.trim()
    if (!normalized || normalized === expectedName) {
      return
    }
    void executeCommands([{ type: 'RENAME_NODE', nodeId, expectedName, name: normalized }]).catch(() => undefined)
  }, [executeCommands])

  const createFunctionNode = useCallback((outputKind: 'IMAGE' | 'VIDEO') => {
    const fnDef = models?.find((item) =>
      item.outputs?.some((o) => o.kind === outputKind) && item.available !== false
    ) ?? models?.find((item) => item.outputs?.some((o) => o.kind === outputKind))
    if (!fnDef || fnDef.available === false) {
      setToast(fnDef?.unavailableReason || `没有可用的${outputKind === 'IMAGE' ? '图片' : '视频'}模型`)
      return
    }
    const alias = reserveNodeAlias(outputKind === 'IMAGE' ? '图片生成' : '视频生成')
    const nodeId = crypto.randomUUID()
    const defaultConfig = createDefaultFunctionConfig(fnDef)
    defaultConfig.prompt = outputKind === 'IMAGE' ? '描述要生成的图片' : '描述要生成的视频'
    void executeCommands([
      { type: 'CREATE_NODE', nodeId, name: alias, transform: nextTransform(), resources: [] },
      {
        type: 'SET_NODE_FUNCTION',
        nodeId,
        expectedFunction: null,
        function: { name: fnDef.name, args: configToFunctionArgs(defaultConfig, fnDef) },
      },
    ]).catch(() => undefined).finally(() => releaseNodeAlias(alias))
  }, [executeCommands, models, nextTransform, releaseNodeAlias, reserveNodeAlias, setToast])

  const handleAddAction = useCallback((action: AddMenuAction) => {
    setState((current) => ({ ...current, addMenuOpen: false }))
    if (action === 'text-resource') {
      createTextNode()
    } else if (action === 'image-function') {
      createFunctionNode('IMAGE')
    } else if (action === 'video-function') {
      createFunctionNode('VIDEO')
    }
  }, [createFunctionNode, createTextNode, setState])

  const createGroup = useCallback(() => {
    const projected = snapshot ? projectCanvasSnapshot(snapshot) : null
    const members = selectedIds
      .map((id) => projected?.resourceNodes.find((node) => node.id === id))
      .filter((node): node is ResourceNode => Boolean(node))
    if (members.length === 0 || members.length !== selectedIds.length || members.some((node) => node.groupId)) {
      setToast('请先选择未分组的资源节点。')
      return
    }
    const memberTransforms = members.map((node) => {
      const draft = positionDrafts[node.id]
      return draft ? { ...node.transform, ...draft } : node.transform
    })
    const minX = Math.min(...memberTransforms.map((transform) => transform.x)) - 32
    const minY = Math.min(...memberTransforms.map((transform) => transform.y)) - 52
    const maxX = Math.max(...memberTransforms.map((transform) => transform.x + transform.width)) + 32
    const maxY = Math.max(...memberTransforms.map((transform) => transform.y + transform.height)) + 32
    const groupId = crypto.randomUUID()
    void executeCommands([
      {
        type: 'CREATE_GROUP',
        groupId,
        title: '分组',
        transform: { x: minX, y: minY, width: maxX - minX, height: maxY - minY },
      },
      ...members.map((node) => ({
        type: 'SET_NODE_GROUP' as const,
        nodeId: node.id,
        expectedGroupId: node.groupId,
        groupId,
      })),
    ]).catch(() => undefined)
  }, [executeCommands, positionDrafts, selectedIds, setToast, snapshot])

  const ungroupGroup = useCallback((groupId: string) => {
    const memberNodes = (snapshot?.nodes ?? []).filter((node) => node.groupId === groupId)
    if (memberNodes.length === 0) {
      setToast('当前分组没有成员。')
      return
    }
    void executeCommands(memberNodes.map((node) => ({
      type: 'SET_NODE_GROUP',
      nodeId: node.id,
      expectedGroupId: groupId,
      groupId: null,
    }))).catch(() => undefined)
  }, [executeCommands, setToast, snapshot?.nodes])

  const renameGroup = useCallback((groupId: string, title: string, expectedTitle: string) => {
    const normalized = title.trim()
    if (!normalized || normalized === expectedTitle) {
      return
    }
    void executeCommands([{ type: 'RENAME_GROUP', groupId, expectedTitle, title: normalized }]).catch(() => undefined)
  }, [executeCommands])

  const deleteGroup = useCallback((groupId: string) => {
    const memberIds = (snapshot?.nodes ?? []).filter((n) => n.groupId === groupId).map((n) => n.id)
    void executeCommands([
      ...memberIds.map((nodeId) => ({
        type: 'SET_NODE_GROUP' as const,
        nodeId,
        expectedGroupId: groupId,
        groupId: null,
      })),
      { type: 'DELETE_GROUP', groupId, expectedMemberNodeIds: memberIds },
    ]).catch(() => undefined)
  }, [executeCommands, snapshot?.nodes])

  const deleteSelection = useCallback(() => {
    if (!snapshot) {
      return
    }
    const commands: CanvasCommandDTO[] = []
    for (const id of selectedIds) {
      const node = snapshot.nodes.find((n) => n.id === id)
      if (node) {
        commands.push({
          type: 'DELETE_NODE',
          nodeId: node.id,
          expectedResourceIds: node.resources.map((r) => r.id),
          expectedFunction: node.function ? { name: node.function.name, args: node.function.args } : null,
        })
      }
      const group = snapshot.groups.find((g) => g.id === id)
      if (group) {
        const members = snapshot.nodes.filter((n) => n.groupId === group.id).map((n) => n.id)
        for (const memberId of members) {
          commands.push({ type: 'SET_NODE_GROUP', nodeId: memberId, expectedGroupId: group.id, groupId: null })
        }
        commands.push({ type: 'DELETE_GROUP', groupId: group.id, expectedMemberNodeIds: members })
      }
    }
    if (commands.length === 0) {
      return
    }
    void executeCommands(commands).then(() => {
      setState((current) => ({ ...current, selectedIds: [], selectedLinks: [] }))
    }).catch(() => undefined)
  }, [executeCommands, selectedIds, setState, snapshot])

  const deleteNode = useCallback((nodeId: string) => {
    const node = snapshot?.nodes.find((n) => n.id === nodeId)
    void flushFunctionConfig(nodeId)
      .catch(() => undefined)
      .then(() => executeCommands([{
        type: 'DELETE_NODE',
        nodeId,
        expectedResourceIds: node ? node.resources.map((r) => r.id) : [],
        expectedFunction: node?.function ? { name: node.function.name, args: node.function.args } : null,
      }]))
      .then(() => {
        setState((current) => ({
          ...current,
          selectedIds: current.selectedIds.filter((id) => id !== nodeId),
          selectedLinks: current.selectedLinks.filter((link) => (
            link.sourceNodeId !== nodeId && link.targetNodeId !== nodeId
          )),
        }))
      })
      .catch(() => undefined)
  }, [executeCommands, flushFunctionConfig, setState, snapshot])

  const saveDraftAsNewNode = useCallback(async (nodeId: string) => {
    const draft = drafts[nodeId]
    if (!draft) {
      return
    }
    const newId = crypto.randomUUID()
    const text = draft.text?.markdown ?? ''
    const commands: CanvasCommandDTO[] = [{
      type: 'CREATE_NODE',
      nodeId: newId,
      name: draft.text?.name ?? suggestNodeAlias('新节点'),
      transform: nextTransform(),
      resources: text ? [{ kind: 'TEXT', name: 'text', textContent: text }] : [],
    }]
    if (draft.function) {
      commands.push({ type: 'SET_NODE_FUNCTION', nodeId: newId, expectedFunction: null, function: draft.function })
    }
    try {
      await executeCommands(commands)
      dismissDraft(nodeId)
      setToast('已另存为新节点')
    } catch {
      setToast('另存为新节点失败')
    }
  }, [dismissDraft, drafts, executeCommands, nextTransform, setToast, suggestNodeAlias])

  const retryDraft = useCallback(async (nodeId: string) => {
    const draft = drafts[nodeId]
    if (!draft) {
      return
    }
    const remoteNode = currentCanvasSnapshot(scope, snapshot)?.nodes.find((n) => n.id === nodeId)
    if (!remoteNode) {
      setToast('远端节点已被删除，请选择另存为新节点')
      return
    }
    const commands: CanvasCommandDTO[] = []
    if (draft.text && draft.text.markdown !== undefined) {
      commands.push({
        type: 'SET_NODE_RESOURCES',
        nodeId: nodeId,
        expectedResourceIds: remoteNode.resources.map((r) => r.id),
        resources: [{ kind: 'TEXT', name: 'text', textContent: draft.text.markdown }],
      })
      if (draft.text.name && draft.text.name !== remoteNode.name) {
        commands.push({
          type: 'RENAME_NODE',
          nodeId: nodeId,
          expectedName: remoteNode.name,
          name: draft.text.name,
        })
      }
    }
    if (draft.function !== undefined) {
      commands.push({
        type: 'SET_NODE_FUNCTION',
        nodeId: nodeId,
        expectedFunction: remoteNode.function,
        function: draft.function,
      })
    }
    try {
      await executeCommands(commands)
      dismissDraft(nodeId)
      setToast('重试成功')
    } catch {
      // 冲突处理分支会自动刷新草稿状态
    }
  }, [dismissDraft, drafts, executeCommands, scope, setToast, snapshot])

  return {
    renameNode,
    createFunctionNode,
    handleAddAction,
    createGroup,
    ungroupGroup,
    renameGroup,
    deleteGroup,
    deleteSelection,
    deleteNode,
    saveDraftAsNewNode,
    retryDraft,
  }
}
