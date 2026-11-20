import { useCallback } from 'react'
import { currentCanvasSnapshot, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'
import { projectCanvasSnapshot, type ResourceNode } from '@/features/canvas/domain'
import type { FunctionConfigSync } from '@/features/canvas/function-config'
import { configToFunctionArgs, createDefaultFunctionConfig } from '@/features/canvas/generation'
import {
  deleteGroupCommand,
  deleteNodeCommand,
  deleteSelectionCommands,
  groupBounds,
  groupMemberTransforms,
} from '@/features/canvas/node-commands'
import type { AddMenuAction, CanvasLocalState } from '@/features/canvas/types'
import type {
  CanvasCommandDTO,
  CanvasCommandDeleteGroup,
  CanvasCommandDeleteNode,
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
  flushFunctionConfig: FunctionConfigSync['flushFunctionConfig']
  lockFunctionConfigsForDeletion: FunctionConfigSync['lockFunctionConfigsForDeletion']
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
  lockFunctionConfigsForDeletion,
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
    const memberTransforms = groupMemberTransforms(members, positionDrafts)
    const groupId = crypto.randomUUID()
    void executeCommands([
      {
        type: 'CREATE_GROUP',
        groupId,
        title: '分组',
        transform: groupBounds(memberTransforms),
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

  const deleteCommands = useCallback(async (
    commands: (CanvasCommandDeleteGroup | CanvasCommandDeleteNode)[],
    onDeleted?: () => void,
  ) => {
    const canvasId = snapshot?.document.id
    const epoch = scope.canvasEpochRef.current
    const isCurrent = () => scope.isMountedRef.current
      && scope.canvasEpochRef.current === epoch && scope.activeCanvasIdRef.current === canvasId
    if (!isCurrent() || commands.length === 0) {
      return
    }
    const nodeIds = commands.filter((command) => command.type === 'DELETE_NODE').map((command) => command.nodeId)
    const unlock = lockFunctionConfigsForDeletion(nodeIds)
    if (!unlock) {
      return
    }
    try {
      const prepared = await Promise.all(commands.map(async (command) => {
        if (command.type === 'DELETE_NODE') {
          const savedFunction = await flushFunctionConfig(command.nodeId, command.expectedFunction ?? null)
          if (savedFunction !== undefined) {
            // 只接受本次 flush 的 ACK；远端变更仍由点击时冻结的基线裁决。
            return { ...command, expectedFunction: savedFunction }
          }
        }
        return command
      }))
      if (!isCurrent()) {
        return
      }
      await executeCommands(prepared)
      if (isCurrent()) {
        onDeleted?.()
      }
    } catch {
      // 保存或删除失败均由命令队列提示，不继续删除或清理选区。
    } finally {
      unlock()
    }
  }, [executeCommands, flushFunctionConfig, lockFunctionConfigsForDeletion, scope, snapshot?.document.id])

  const deleteGroup = useCallback((groupId: string) => {
    if (snapshot?.groups.some((group) => group.id === groupId)) {
      return deleteCommands([deleteGroupCommand(snapshot, groupId)])
    }
  }, [deleteCommands, snapshot])

  const deleteSelection = useCallback(() => {
    if (snapshot) {
      return deleteCommands(deleteSelectionCommands(snapshot, selectedIds), () => {
        setState((current) => ({ ...current, selectedIds: [], selectedLinks: [] }))
      })
    }
  }, [deleteCommands, selectedIds, setState, snapshot])

  const deleteNode = useCallback((nodeId: string) => {
    const node = snapshot?.nodes.find((item) => item.id === nodeId)
    if (node) {
      return deleteCommands([deleteNodeCommand(node)], () => {
        setState((current) => ({
          ...current,
          selectedIds: current.selectedIds.filter((id) => id !== nodeId),
          selectedLinks: current.selectedLinks.filter((link) => (
            link.sourceNodeId !== nodeId && link.targetNodeId !== nodeId
          )),
        }))
      })
    }
  }, [deleteCommands, setState, snapshot])

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
      resources: text ? [{ type: 'TEXT', name: 'text', textContent: text }] : [],
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
        resources: [{ type: 'TEXT', name: 'text', textContent: draft.text.markdown }],
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
