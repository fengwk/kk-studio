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
import type {
  AgentRuntimeOwnerDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
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
 * 精确比较非负十进制 revision 字符串（后端经 Long.toString 生成，可能远超
 * Number.MAX_SAFE_INTEGER）。用 BigInt 避免 double 精度丢失导致回退误判。
 * 返回 -1 / 0 / 1。
 */
function compareDecimalRevisions(a: string, b: string): number {
  const bigA = BigInt(a)
  const bigB = BigInt(b)
  if (bigA < bigB) {
    return -1
  }
  if (bigA > bigB) {
    return 1
  }
  return 0
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
 *   activeTools，冻结其余选中值）；
 * - YOLO 走直接控制面：写请求串行并合并快速连点（每次基于最新权威 revision，
 *   latest wins），重绑时以 generation 使旧 Thread 的迟到响应/错误整体失效。
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
  owner,
  threadId,
  initialParts = [],
  initialReplay,
}: {
  owner: AgentRuntimeOwnerDTO
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
  // YOLO 直接控制面的权威 Thread 状态（revision + yolo policy）：来自 /yolo 成功
  // 响应，或非回退的 snapshot。后续每次 CAS 都基于它，而不是可能滞后的
  // controller snapshot —— 连续切换不会因 revision 过期而互相踩踏。
  const authoritativeThreadRef = useRef<HarnessThreadDTO | null>(null)
  // 串行并合并快速连点：yoloPendingRef 只保留最新用户意图；每个写请求开始时读取
  // 最新权威 revision，上一次响应返回后再发下一次，latest wins。
  const yoloPendingRef = useRef<boolean | null>(null)
  const yoloDrainingRef = useRef(false)
  // YOLO 更新 generation：每次重新绑定到另一个 Thread 时递增，使旧 Thread 的迟到
  // 成功/失败都无法污染新面板（不改 draft、不设 yoloError）。
  const yoloGenerationRef = useRef(0)

  // 重新绑定到另一个 Thread 时清空面板本地 draft，并从新 snapshot 重新初始化
  //（controller 也会重置其 stop/decision replay 状态）。Interaction/错误/确认等
  // 其它本地状态由调用组件在 threadId 变化时自行重置。render-time fail-closed
  // 已保证重绑 render 不暴露旧值，此 effect 仅尽快释放旧 state。
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    // 使旧 Thread 的在途 YOLO 请求全部失效，并丢弃陈旧权威 revision / 未发意图。
    yoloGenerationRef.current += 1
    authoritativeThreadRef.current = null
    yoloPendingRef.current = null
    setBranchState(null)
    setYoloError(null)
  }, [threadId])

  // 从持久化 Thread snapshot 初始化或跟随 base；用户 draft 绝不会被静默覆盖。
  // 只接受属于当前 threadId 的快照：重绑后新 snapshot 未到达前不初始化；
  // revision 回退的迟到 snapshot 被整体忽略（不改 base、不降权威 revision）。
  useEffect(() => {
    const thread = controller.thread
    if (!thread || thread.threadId !== threadId) {
      return
    }
    if (!adoptAuthoritative(thread)) {
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
        owner,
        thread: boundThread,
        effectiveBase,
        draft: boundBranchState.draft,
        parts,
      })
    },
    [boundBranchState, boundThread, effectiveBase, owner],
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
   * 尝试接受一个新的权威 Thread 快照：只接受同线程且不使 revision 回退的值
   * （精确十进制比较），避免迟到的 /yolo 前 snapshot 覆盖已确认的新 revision。
   * 返回是否被采纳；调用方在拒绝时应整体忽略该 snapshot，避免用回退值改写 base。
   */
  function adoptAuthoritative(thread: HarnessThreadDTO): boolean {
    const current = authoritativeThreadRef.current
    if (
      current != null
      && current.threadId === thread.threadId
      && compareDecimalRevisions(thread.revision, current.revision) < 0
    ) {
      return false
    }
    authoritativeThreadRef.current = thread
    return true
  }

  /**
   * 切换 Thread YOLO runtime policy：乐观更新 draft 后入队直接控制面写（PUT
   * /yolo）。写请求串行执行并合并快速连点 —— 每次发送都基于最新权威 revision
   * （上次 /yolo 成功返回或非回退 snapshot），latest wins。成功后把 base 与 draft
   * 的 yolo 对齐服务器权威值（其它未发送 settings 原样保留）并采纳新 revision；
   * 失败则把 draft 回滚到 base 值并暴露 yoloError。绝不生成 SET_YOLO command。
   */
  function setYoloEnabled(enabled: boolean) {
    if (boundBranchState == null || boundThread == null) {
      return
    }
    setYoloError(null)
    editDraft({ yoloEnabled: enabled })
    yoloPendingRef.current = enabled
    void drainYolo()
  }

  /** 串行执行 YOLO 写：同一时刻至多一个在途请求，快速连点合并到最新目标。 */
  async function drainYolo() {
    if (yoloDrainingRef.current) {
      return
    }
    yoloDrainingRef.current = true
    try {
      while (yoloPendingRef.current != null) {
        const generation = yoloGenerationRef.current
        const target = yoloPendingRef.current
        yoloPendingRef.current = null
        const authoritative = authoritativeThreadRef.current
        if (authoritative == null || authoritative.threadId !== boundThreadIdRef.current) {
          // 重绑后缺乏新 Thread 的权威 revision：丢弃这次尝试；新绑定后的
          // setYoloEnabled 会以新 generation 重新入队。
          continue
        }
        await writeYolo(generation, authoritative, target)
      }
    } finally {
      yoloDrainingRef.current = false
    }
  }

  /** 发出单次 YOLO 写；任何迟到（旧 Thread / 旧 generation）结果都静默丢弃。 */
  async function writeYolo(
    generation: number,
    authoritative: HarnessThreadDTO,
    enabled: boolean,
  ) {
    try {
      const updated = await harnessService.setThreadYolo(authoritative.threadId, {
        expectedRevision: authoritative.revision,
        yoloEnabled: enabled,
      })
      if (
        generation !== yoloGenerationRef.current
        || updated.threadId !== boundThreadIdRef.current
      ) {
        return
      }
      adoptAuthoritative(updated)
      setYoloError(null)
      // 捕获成功时刻的排队意图：React 会延迟执行 setBranchState 回调，届时
      // pending 可能已被 drain 消费为 null，导致误判“无更新意图”而压掉乐观 draft。
      const hasNewerIntent = yoloPendingRef.current != null
      setBranchState((current) => {
        if (current == null || current.threadId !== threadId) {
          return current
        }
        // 排队意图时保留乐观 draft；base 恒跟随服务器确认值。
        return {
          ...current,
          base: { ...current.base, yoloEnabled: updated.yoloEnabled },
          draft: hasNewerIntent
            ? current.draft
            : { ...current.draft, yoloEnabled: updated.yoloEnabled },
        }
      })
    } catch (error) {
      if (
        generation !== yoloGenerationRef.current
        || authoritative.threadId !== boundThreadIdRef.current
      ) {
        return
      }
      if (yoloPendingRef.current != null) {
        // 过期中间请求失败且已有更新意图在排队：不动 draft、不产生瞬时错误，
        // 交给后续请求出结果（latest wins）。
        return
      }
      setBranchState((current) =>
        current == null
          || current.threadId !== threadId
          || current.base.yoloEnabled === enabled
          ? current
          : { ...current, draft: { ...current.draft, yoloEnabled: current.base.yoloEnabled } },
      )
      setYoloError(errorMessage(error))
    }
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
    adoptAuthoritative(thread)
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
