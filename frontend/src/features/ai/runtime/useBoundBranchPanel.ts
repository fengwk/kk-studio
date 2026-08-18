import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  activeToolsFromAgent,
  branchDraftFromThread,
  branchDraftsEqual,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { EnvironmentBindingDTO } from '@/shared/api/contracts/ai-environment'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import {
  useAgentThreadController,
  type CommandBatchReplay,
} from '@/features/ai/runtime/useAgentThreadController'
import { harnessService } from '@/shared/api/harness-service'
import { translate } from '@/shared/i18n'

/**
 * 面板本地 branch draft：从持久化的 Thread snapshot 初始化，面板本地编辑，
 * 与下一条 message batch 一起原子应用。base 跟随 snapshot；queued SET_*
 * command 投影 effective base，避免连续发送时重复携带在途中的 settings。
 *
 * state 携带其所属 threadId：重绑后、reset effect 与新 snapshot 到达之前，
 * 旧 state 不会通过任何返回值或 batch 构建器泄漏到新 Thread。
 */
interface BoundBranchState {
  threadId: string
  base: BranchDraft
  draft: BranchDraft
}

/** 请求失败时把原始错误映射为可读 message（与 controller 同风格）。 */
function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return translate('ai.runtime.action.updateYoloFailed')
}

/**
 * 绑定 Thread 面板的共享编排（Bound Chat 与 Canvas Bound 同一事实源）：
 *
 * - `buildBatchRef` 转发 + `useAgentThreadController` 的循环桥接（submit handler
 *   事件驱动地读取最新 batch 构建器）；
 * - branch base/draft 从 snapshot 初始化、thread 重绑重置，以及 queued SET_*
 *   pending projection 出的 effectiveBase；
 * - 通过 `buildMessageBatchPlan` 构建原子 message batch；
 * - agent/environment/yolo/model 的 draft-local 编辑（agent 选择采用其
 *   activeTools，冻结其余选中值）。
 *
 * 重绑是 render-time fail-closed：只有 branch state 与 controller snapshot 都
 * 属于当前 `threadId` 时，才返回 draft/effectiveBase 并允许 build batch；否则
 * 全部为 null（调用方的 composer disabled 门控生效），旧 Thread 的 draft 绝不
 * 会被发送到新 Thread。
 *
 * 场景差异（命令、交互面板、通知、focus、thread/tree/new/rebind 门控）只保留
 * 在调用组件中。
 */
export function useBoundBranchPanel({
  threadId,
  initialParts = [],
  initialReplay,
}: {
  threadId: string
  initialParts?: ComposerPart[]
  initialReplay?: CommandBatchReplay
}) {
  // buildBatch 依赖 controller 的 snapshot thread；稳定回调通过 ref 转发，
  // 并在提交事件到达前由下方 effect 更新。
  const buildBatchRef = useRef<((parts: ComposerPart[]) => CommandBatchPlan | null) | null>(null)
  const controller = useAgentThreadController(
    threadId,
    initialParts,
    initialReplay,
    (parts) => buildBatchRef.current?.(parts) ?? null,
  )
  const [branchState, setBranchState] = useState<BoundBranchState | null>(null)
  const [yoloError, setYoloError] = useState<string | null>(null)
  const boundThreadIdRef = useRef<string | null>(null)
  // YOLO 直接更新请求序号：只有最新的响应才允许写回（乐观编辑/回滚均以最新请求为准）。
  const yoloRequestIdRef = useRef(0)

  // 重新绑定到另一个 Thread 时清空面板本地 draft，并从新 snapshot 重新初始化
  //（controller 也会重置其 stop/decision replay 状态）。Interaction/错误/确认等
  // 其它本地状态由调用组件在 threadId 变化时自行重置。render-time fail-closed
  // 已保证重绑 render 不暴露旧值，此 effect 仅尽快释放旧 state。
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    setBranchState(null)
    setYoloError(null)
  }, [threadId])

  // 从持久化 Thread snapshot 初始化或跟随 base；用户 draft 绝不会被静默覆盖。
  // 只接受属于当前 threadId 的快照：重绑后新 snapshot 未到达前不初始化。
  useEffect(() => {
    const thread = controller.thread
    if (!thread || thread.threadId !== threadId) {
      return
    }
    setBranchState((current) => {
      const snapshotDraft = branchDraftFromThread(thread)
      if (current == null || current.threadId !== threadId) {
        return {
          threadId,
          base: snapshotDraft,
          draft: projectPendingTarget(snapshotDraft, controller.queuedCommands),
        }
      }
      return { ...current, base: snapshotDraft }
    })
  }, [controller.queuedCommands, controller.thread, threadId])

  // render-time fail-closed：state 与 snapshot 必须都属于当前 threadId。
  const boundThread = controller.thread?.threadId === threadId ? controller.thread : null
  const boundBranchState = branchState?.threadId === threadId ? branchState : null

  const effectiveBase = useMemo(
    () => (boundBranchState == null || boundThread == null
      ? null
      : projectPendingTarget(boundBranchState.base, controller.queuedCommands)),
    [boundBranchState, boundThread, controller.queuedCommands],
  )

  const buildBatch = useCallback(
    (parts: ComposerPart[]): CommandBatchPlan | null => {
      if (boundThread == null || boundBranchState == null || effectiveBase == null) {
        return null
      }
      return buildMessageBatchPlan({
        thread: boundThread,
        effectiveBase,
        draft: boundBranchState.draft,
        parts,
      })
    },
    [boundBranchState, boundThread, effectiveBase],
  )
  useEffect(() => {
    // Ref 由 controller 的 submit handler 使用（事件驱动，总在 effect 之后）。
    buildBatchRef.current = buildBatch
  })

  const dirty =
    boundBranchState != null
    && boundThread != null
    && effectiveBase != null
    && !branchDraftsEqual(effectiveBase, boundBranchState.draft)

  function editDraft(patch: Partial<BranchDraft>) {
    setBranchState((current) =>
      current == null || current.threadId !== threadId
        ? current
        : { ...current, draft: { ...current.draft, ...patch } },
    )
  }

  /**
   * Draft-local agent 选择：解析 catalog（与 Agent picker 同源）并采用其
   * activeTools；冻结的 model/environment/yolo 选中值保持不变。返回是否解析
   * 成功，由调用方决定错误反馈。
   */
  function selectAgent(agentName: string): boolean {
    const agent = controller.agents.find((candidate) => candidate.name === agentName)
    if (agent == null) {
      return false
    }
    editDraft({ agentName, activeTools: activeToolsFromAgent(agent) })
    return true
  }

  function selectEnvironment(environment: EnvironmentBindingDTO | null) {
    editDraft({ environment })
  }

  /**
   * 切换 Thread YOLO runtime policy：乐观更新 draft 后立即调用直接控制面
   * （PUT /yolo，基于当前 snapshot revision 的精确 CAS）。成功后只把 base 与
   * draft 的 yolo 对齐服务器权威值（其它未发送 settings 原样保留）；失败则把
   * draft 回滚到 base 值，并通过 {@code yoloError} 暴露错误。绝不生成
   * SET_YOLO command。并发连点以最新请求为准（CAS 失败时旧请求整体回滚，
   * 新请求已携带新 revision 重试）。
   */
  function setYoloEnabled(enabled: boolean) {
    if (boundBranchState == null || boundThread == null) {
      return
    }
    const thread = boundThread
    setYoloError(null)
    editDraft({ yoloEnabled: enabled })
    const requestId = ++yoloRequestIdRef.current
    void harnessService
      .setThreadYolo(thread.threadId, {
        expectedRevision: thread.revision,
        yoloEnabled: enabled,
      })
      .then((updated: HarnessThreadDTO) => {
        if (requestId !== yoloRequestIdRef.current) {
          return
        }
        setBranchState((current) =>
          current == null || current.threadId !== thread.threadId
            ? current
            : {
                ...current,
                base: { ...current.base, yoloEnabled: updated.yoloEnabled },
                draft: { ...current.draft, yoloEnabled: updated.yoloEnabled },
              },
        )
      })
      .catch((error: unknown) => {
        if (requestId !== yoloRequestIdRef.current) {
          return
        }
        setBranchState((current) =>
          current == null
            || current.threadId !== thread.threadId
            || current.base.yoloEnabled === enabled
            ? current
            : { ...current, draft: { ...current.draft, yoloEnabled: current.base.yoloEnabled } },
        )
        setYoloError(errorMessage(error))
      })
  }

  function selectModel(model: BranchDraft['model']) {
    editDraft({ model })
  }

  /**
   * 用权威 Thread DTO 完整重载 base + draft（base === draft，干净）。用于 head
   * 重定位成功后从返回的 Thread 重新初始化（yolo 保留服务器值；settings 取自 DTO）。
   * 非当前 Thread 的 DTO 会被拒绝，绝不写入。
   */
  function resetDraftFromThread(thread: HarnessThreadDTO) {
    if (thread.threadId !== threadId) {
      return
    }
    const snapshotDraft = branchDraftFromThread(thread)
    setBranchState({ threadId, base: snapshotDraft, draft: snapshotDraft })
  }

  return {
    controller,
    branchState: boundBranchState,
    effectiveBase,
    draft: boundBranchState?.draft,
    dirty,
    yoloError,
    dismissYoloError: () => setYoloError(null),
    selectAgent,
    selectEnvironment,
    setYoloEnabled,
    selectModel,
    resetDraftFromThread,
  }
}
