import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  branchDraftFromThread,
  branchDraftsEqual,
  buildBranchDiffCommands,
  materializeAgentBranchDraft,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  buildGoalBatchPlan,
  buildMessageBatchPlan,
  createCommandId,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type {
  HarnessThreadCommandDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { ThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import {
  useAgentThreadController,
} from '@/features/ai/runtime/useAgentThreadController'
import { buildThreadCommandBatchRequest } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import { harnessService } from '@/shared/api/harness-service'
import { isConflictReason } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate } from '@/shared/i18n'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'

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

/**
 * 本地已提交、但服务器尚未回读到 snapshot 的 SET_* 命令 overlay。
 *
 * watermarkVersion 记录接受响应里权威 Thread 的 version：command 的持久化与 version
 * 在同一事务内提交，因此任何 version 达到该水位的快照都已包含该命令（排队中或已应用）。
 * 达到水位后条目即失效，绝不长期遮挡服务器的最新事实。按 (threadId, sequence) 由
 * idempotencyKey 与快照排队命令去重。
 */
interface SettingsOverlayEntry {
  threadId: string
  command: HarnessThreadCommandDTO
  watermarkVersion: string
}

/** 仅 STALE_COMMAND_CURSOR 允许借既有冲突回读机制重建一次 cursor/diff，不另造任意重试。 */
const MAX_STALE_SETTINGS_CURSOR_RETRIES = 2

/** Thread 是否运行中：只有 IDLE/STOPPED 是静止态，其余（排队/模型/工具/应用）都表示 busy。 */
function isBusyThread(thread: HarnessThreadDTO): boolean {
  return thread.processing || (thread.status !== 'IDLE' && thread.status !== 'STOPPED')
}

/**
 * 把本地 overlay 命令并入服务器排队命令供 projection 使用：服务器已回读的命令以服务器
 * 事实为准（按 idempotencyKey 去重），overlay 只补尚未回读的差集。
 */
function mergeQueuedCommands(
  serverQueued: readonly HarnessThreadCommandDTO[],
  overlay: readonly SettingsOverlayEntry[],
  threadId: string,
): HarnessThreadCommandDTO[] {
  if (overlay.length === 0) {
    return [...serverQueued]
  }
  const observed = new Set(serverQueued.map((command) => command.idempotencyKey))
  const extra = overlay
    .filter((entry) => entry.threadId === threadId)
    .map((entry) => entry.command)
    .filter((command) => !observed.has(command.idempotencyKey))
  return extra.length === 0 ? [...serverQueued] : [...serverQueued, ...extra]
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
 * 精确比较非负十进制 version 字符串（后端经 Long.toString 生成，可能远超
 * Number.MAX_SAFE_INTEGER）。用 BigInt 避免 double 精度丢失导致回退误判。
 * 返回 -1 / 0 / 1。
 */
function compareDecimalVersions(a: string, b: string): number {
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
 * - `buildBatchRef` / `buildGoalBatchRef` 转发 + `useAgentThreadController` 的循环桥接
 *   （submit handler 事件驱动地读取最新 batch 构建器）；
 * - branch base/draft 从 snapshot 初始化、thread 重绑重置，以及 queued SET_*
 *   pending projection 出的 effectiveBase；
 * - 通过 `buildMessageBatchPlan` / `buildGoalBatchPlan` 构建原子 thread command batch；
 * - agent/model 的 draft-local 编辑（agent 选择冻结其余选中值）；
 * - YOLO 走单字段策略控制面：写请求串行并合并快速连点（latest wins），
 *   重绑时以 generation 使旧 Thread 的迟到响应/错误整体失效。
 *
 * 重绑是 render-time fail-closed：只有 branch state 与 controller snapshot 都
 * 属于当前 `threadId` 时，才返回 draft/effectiveBase 并允许 build batch；否则
 * 全部为 null（调用方的 composer disabled 门控生效），旧 Thread 的 draft 绝不
 * 会被发送到新 Thread。
 *
 * 场景差异（命令、交互面板、通知、focus、thread/history/new/rebind 门控）只保留
 * 在调用组件中。
 */
export function useBoundBranchPanel({
  threadId,
  initialParts = [],
  projection,
}: {
  threadId: string
  initialParts?: ComposerPart[]
  /** 调用方已持有的只读投影；传入时不重复查询与订阅。 */
  projection?: ThreadProjection
}) {
  // buildBatch 依赖 controller 的 snapshot thread；稳定回调通过 ref 转发，
  // 并在提交事件到达前由下方 effect 更新。
  const buildBatchRef = useRef<((parts: ComposerPart[]) => CommandBatchPlan | null) | null>(null)
  const buildGoalBatchRef = useRef<((goalText: string | null) => CommandBatchPlan | null) | null>(null)
  const controller = useAgentThreadController(
    threadId,
    initialParts,
    (parts) => buildBatchRef.current?.(parts) ?? null,
    (goalText) => buildGoalBatchRef.current?.(goalText) ?? null,
    projection,
  )
  const [branchState, setBranchState] = useState<BoundBranchState | null>(null)
  const [yoloError, setYoloError] = useState<string | null>(null)
  const [settingsError, setSettingsError] = useState<string | null>(null)
  const [settingsOverlay, setSettingsOverlay] = useState<SettingsOverlayEntry[]>([])
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const boundThreadIdRef = useRef<string | null>(null)
  const queryClient = useQueryClient()
  // YOLO 直接控制面的权威 Thread 状态（version + yolo policy）：来自 /yolo 成功
  // 响应，或非回退的 snapshot。后续每次 CAS 都基于它，而不是可能滞后的
  // controller snapshot —— 连续切换不会因 version 过期而互相踩踏。
  const authoritativeThreadRef = useRef<HarnessThreadDTO | null>(null)
  // 串行并合并快速连点：yoloPendingRef 只保留最新用户意图；每个写请求开始时读取
  // 最新权威 version，上一次响应返回后再发下一次，latest wins。
  const yoloPendingRef = useRef<boolean | null>(null)
  const yoloDrainingRef = useRef(false)
  // YOLO 更新 generation：每次重新绑定到另一个 Thread 时递增，使旧 Thread 的迟到
  // 成功/失败都无法污染新面板（不改 draft、不设 yoloError）。
  const yoloGenerationRef = useRef(0)
  // 设置即时提交：与 YOLO 同构的串行 latest-intent 结构（独立，不与 composer 输入
  // 的 pending message 合并）。settingsIntentRef 只保留最新目标选择。
  const settingsIntentRef = useRef<BranchDraft | null>(null)
  const settingsDrainingRef = useRef(false)
  const settingsGenerationRef = useRef(0)
  // 每次 render 同步最新值，供 drain 的 await 之后读取（避免闭包/迟到渲染读数过期）。
  const boundThreadRef = useRef<HarnessThreadDTO | null>(null)
  const baseRef = useRef<BranchDraft | null>(null)
  const draftRef = useRef<BranchDraft | null>(null)
  const queuedCommandsRef = useRef<readonly HarnessThreadCommandDTO[]>([])
  const overlayRef = useRef<SettingsOverlayEntry[]>([])

  // 重新绑定到另一个 Thread 时清空面板本地 draft，并从新 snapshot 重新初始化
  //（controller 也会重置其 stop/decision replay 状态）。Interaction/错误/确认等
  // 其它本地状态由调用组件在 threadId 变化时自行重置。render-time fail-closed
  // 已保证重绑 render 不暴露旧值，此 effect 仅尽快释放旧 state。
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    // 使旧 Thread 的在途 YOLO / 设置请求全部失效，并丢弃陈旧权威 version / 未发意图 / overlay。
    yoloGenerationRef.current += 1
    authoritativeThreadRef.current = null
    yoloPendingRef.current = null
    settingsGenerationRef.current += 1
    settingsIntentRef.current = null
    overlayRef.current = []
    setBranchState(null)
    setYoloError(null)
    setSettingsOverlay([])
    setSettingsError(null)
    setConflict(null)
  }, [threadId])

  // 从持久化 Thread snapshot 初始化或跟随 base；用户 draft 绝不会被静默覆盖。
  // 只接受属于当前 threadId 的快照：重绑后新 snapshot 未到达前不初始化；
  // version 回退的迟到 snapshot 被整体忽略（不改 base、不降权威 version）。
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
      return {
        ...current,
        base: snapshotDraft,
        draft: yoloDrainingRef.current || yoloPendingRef.current != null
          ? current.draft
          : { ...current.draft, yoloEnabled: snapshotDraft.yoloEnabled },
      }
    })
  }, [controller.queuedCommands, controller.thread, threadId])

  // render-time fail-closed：state 与 snapshot 必须都属于当前 threadId。
  const boundThread = controller.thread?.threadId === threadId ? controller.thread : null
  const boundBranchState = branchState?.threadId === threadId ? branchState : null

  // overlay 只在快照尚未达到其水位时有效：达到后服务器排队命令即权威事实。
  const boundThreadVersion = boundThread?.version
  const activeOverlay = useMemo(
    () => settingsOverlay.filter((entry) =>
      entry.threadId === threadId
      && boundThreadVersion != null
      && compareDecimalVersions(boundThreadVersion, entry.watermarkVersion) < 0),
    [boundThreadVersion, settingsOverlay, threadId],
  )
  useEffect(() => {
    if (settingsOverlay.length === 0) {
      return
    }
    const next = settingsOverlay.filter((entry) =>
      entry.threadId === threadId
      && boundThreadVersion != null
      && compareDecimalVersions(boundThreadVersion, entry.watermarkVersion) < 0)
    if (next.length !== settingsOverlay.length) {
      setSettingsOverlay(next)
    }
  }, [boundThreadVersion, settingsOverlay, threadId])

  // 服务器排队命令 + 尚未回读的本地 overlay：连续选择在 snapshot 刷新前也不会重复携带同一 diff。
  const queuedForProjection = useMemo(
    () => mergeQueuedCommands(controller.queuedCommands, activeOverlay, threadId),
    [activeOverlay, controller.queuedCommands, threadId],
  )

  const effectiveBase = useMemo(
    () => (boundBranchState == null || boundThread == null
      ? null
      : projectPendingTarget(boundBranchState.base, queuedForProjection)),
    [boundBranchState, boundThread, queuedForProjection],
  )

  useEffect(() => {
    boundThreadRef.current = boundThread
    baseRef.current = boundBranchState?.base ?? null
    draftRef.current = boundBranchState?.draft ?? null
    queuedCommandsRef.current = controller.queuedCommands
    overlayRef.current = activeOverlay
  })

  /**
   * drain 在 await 之后按最新镜像重算 effective base（服务器排队命令 + 尚未回读的 overlay），
   * 不依赖上一次 render 的闭包值，连续提交之间不会重复携带同一 SET diff。
   */
  function computeEffectiveBase(): BranchDraft | null {
    const base = baseRef.current
    const currentThreadId = boundThreadIdRef.current
    if (base == null || currentThreadId == null) {
      return null
    }
    return projectPendingTarget(
      base,
      mergeQueuedCommands(queuedCommandsRef.current, overlayRef.current, currentThreadId),
    )
  }

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
  const buildGoalBatch = useCallback(
    (goalText: string | null): CommandBatchPlan | null => {
      if (boundThread == null || boundBranchState == null || effectiveBase == null) {
        return null
      }
      return buildGoalBatchPlan({
        thread: boundThread,
        effectiveBase,
        draft: boundBranchState.draft,
        goalText,
      })
    },
    [boundBranchState, boundThread, effectiveBase],
  )
  useEffect(() => {
    // Refs 由 controller 的 submit handler 使用（事件驱动，总在 effect 之后）。
    buildBatchRef.current = buildBatch
    buildGoalBatchRef.current = buildGoalBatch
  })

  const dirty =
    boundBranchState != null
    && boundThread != null
    && effectiveBase != null
    && !branchDraftsEqual(effectiveBase, boundBranchState.draft)

  // 待生效：仍有 SET_* 命令排队（服务器已回读或本地 overlay 尚未回读）。
  const settingsPending = queuedForProjection.some((command) =>
    command.state === 'QUEUED' && command.type.startsWith('SET_'))
  const settingsStatus: 'draft' | 'pending' | null =
    boundBranchState == null || boundThread == null
      ? null
      : settingsPending
        ? 'pending'
        : dirty
          ? 'draft'
          : null

  /**
   * 同步更新本地 draft 的 state 与 ref 镜像：同一批次内连续选择（setState 尚未提交）也能
   * 看到彼此的结果，绝不会用渲染闭包里的旧值互相覆盖。
   */
  function applyDraft(next: BranchDraft) {
    draftRef.current = next
    setBranchState((current) =>
      current == null || current.threadId !== threadId
        ? current
        : { ...current, draft: next },
    )
  }

  function editDraft(patch: Partial<BranchDraft>) {
    const current = draftRef.current
    if (current == null) {
      return
    }
    applyDraft({ ...current, ...patch })
  }

  /**
   * 应用一次面板本地选择：busy Thread 立即提交 standalone SET-only 批次（等待安全点生效，
   * 以 pending 展示）；idle/uncreated/stopped 保持本地草稿，随下一条输入一起提交。
   */
  function commitDraft(next: BranchDraft) {
    applyDraft(next)
    if (boundThread == null || !isBusyThread(boundThread)) {
      return
    }
    setSettingsError(null)
    settingsIntentRef.current = next
    void drainSettings()
  }

  /**
   * 解析目标 Agent 的模型后原子更新选择；未就绪或配置无效时保留原草稿。
   */
  function selectAgent(agentName: string): boolean {
    const currentDraft = draftRef.current
    if (currentDraft == null || boundThread == null) {
      return false
    }
    const agent = controller.agents.find((candidate) => candidate.name === agentName)
    if (agent == null) {
      return false
    }
    const draft = materializeAgentBranchDraft(
      agent,
      controller.models,
      currentDraft,
    )
    if (draft == null) {
      return false
    }
    commitDraft(draft)
    return true
  }

  /**
   * 尝试接受一个新的权威 Thread 快照：只接受同线程且不使 version 回退的值
   * （精确十进制比较），避免迟到的 /yolo 前 snapshot 覆盖已确认的新 version。
   * 返回是否被采纳；调用方在拒绝时应整体忽略该 snapshot，避免用回退值改写 base。
   */
  function adoptAuthoritative(thread: HarnessThreadDTO): boolean {
    const current = authoritativeThreadRef.current
    if (
      current != null
      && current.threadId === thread.threadId
      && compareDecimalVersions(thread.version, current.version) < 0
    ) {
      return false
    }
    authoritativeThreadRef.current = thread
    return true
  }

  /**
   * 切换 Thread YOLO runtime policy：乐观更新 draft 后入队直接控制面写（PUT
   * /yolo）。写请求串行执行并合并快速连点，latest wins。成功后只采纳不回退的
   * 权威 Thread，并把 base 与 draft 对齐该最新值；较旧响应不覆盖新 SSE。
   * 失败则把 draft 回滚到 base 值并暴露 yoloError。绝不生成 SET_YOLO command。
   */
  function setYoloEnabled(enabled: boolean) {
    if (boundBranchState == null || boundThread == null) {
      return
    }
    setYoloError(null)
    setConflict(null)
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
          // 重绑后缺乏新 Thread 的权威快照：丢弃这次尝试；新绑定后的
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
        yoloEnabled: enabled,
      })
      if (
        generation !== yoloGenerationRef.current
        || updated.threadId !== boundThreadIdRef.current
      ) {
        return
      }
      adoptAuthoritative(updated)
      const accepted = authoritativeThreadRef.current
      if (accepted == null) {
        return
      }
      setYoloError(null)
      setConflict(null)
      // 权威响应携带策略投影：根只可能是 ENABLE/DISABLE，本地 draft 需要的是生效布尔。
      const acceptedEnabled = accepted.yoloPolicy.mode === 'ENABLE'
      // 捕获成功时刻的排队意图：React 会延迟执行 setBranchState 回调，届时
      // pending 可能已被 drain 消费为 null，导致误判“无更新意图”而压掉乐观 draft。
      const hasNewerIntent = yoloPendingRef.current != null
      setBranchState((current) => {
        if (current == null || current.threadId !== threadId) {
          return current
        }
        // 排队意图时保留乐观 draft；base 恒跟随最新权威值。
        return {
          ...current,
          base: { ...current.base, yoloEnabled: acceptedEnabled },
          draft: hasNewerIntent
            ? current.draft
            : { ...current.draft, yoloEnabled: acceptedEnabled },
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
      const presentation = presentConflict(error)
      if (presentation != null) {
        setConflict(presentation)
        setYoloError(null)
      } else {
        setConflict(null)
        setYoloError(errorMessage(error))
      }
    }
  }

  function selectModel(model: BranchDraft['model']) {
    const current = draftRef.current
    if (current == null) {
      return
    }
    commitDraft({ ...current, model })
  }

  function selectEnvironment(environmentName: string | null) {
    const current = draftRef.current
    if (current == null) {
      return
    }
    commitDraft({ ...current, environmentName })
  }

  /** 串行执行设置提交：同一时刻至多一个在途请求，快速连续选择合并到最新目标（latest intent wins）。 */
  async function drainSettings() {
    if (settingsDrainingRef.current) {
      return
    }
    settingsDrainingRef.current = true
    try {
      while (settingsIntentRef.current != null) {
        const generation = settingsGenerationRef.current
        const target = settingsIntentRef.current
        settingsIntentRef.current = null
        await writeSettings(generation, target)
      }
    } finally {
      settingsDrainingRef.current = false
    }
  }

  /**
   * 发出一次 standalone SET-only 批次。cursor 复用权威 Thread（含 /yolo 与上次接受响应）；
   * 仅 STALE_COMMAND_CURSOR 借既有冲突回读机制重建 diff 基准与 cursor 后重试，绝不任意重试。
   * 失败时保留用户选择为本地草稿（随下一条输入提交），并暴露错误。
   */
  async function writeSettings(generation: number, target: BranchDraft) {
    const originThreadId = boundThreadIdRef.current
    for (let attempt = 0; ; attempt += 1) {
      if (generation !== settingsGenerationRef.current || boundThreadIdRef.current !== originThreadId) {
        return
      }
      const sourceThread = authoritativeThreadRef.current ?? boundThreadRef.current
      const base = computeEffectiveBase()
      if (
        originThreadId == null
        || sourceThread == null
        || base == null
        || sourceThread.threadId !== originThreadId
      ) {
        return
      }
      const commands = buildBranchDiffCommands(base, target, createCommandId)
      if (commands.length === 0) {
        return
      }
      const request = buildThreadCommandBatchRequest(sourceThread, commands)
      try {
        const response = await harnessService.acceptThreadCommandBatch(originThreadId, request)
        if (generation !== settingsGenerationRef.current || boundThreadIdRef.current !== originThreadId) {
          return
        }
        adoptAuthoritative(response.thread)
        const accepted = response.acceptedCommands.filter((command) => command.type.startsWith('SET_'))
        if (accepted.length > 0) {
          const entries = accepted.map((command) => ({
            threadId: originThreadId,
            command,
            watermarkVersion: response.thread.version,
          }))
          overlayRef.current = [...overlayRef.current, ...entries]
          setSettingsOverlay((current) => [...current, ...entries])
        }
        setSettingsError(null)
        setConflict(null)
        await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(originThreadId) })
        return
      } catch (error) {
        if (generation !== settingsGenerationRef.current || boundThreadIdRef.current !== originThreadId) {
          return
        }
        if (attempt < MAX_STALE_SETTINGS_CURSOR_RETRIES && isConflictReason(error, 'STALE_COMMAND_CURSOR')) {
          let latest: HarnessThreadSnapshotDTO | null
          try {
            latest = await harnessService.getThreadSnapshot(originThreadId)
          } catch {
            latest = null
          }
          if (
            latest != null
            && generation === settingsGenerationRef.current
            && boundThreadIdRef.current === originThreadId
          ) {
            queryClient.setQueryData(queryKeys.threads.snapshot(originThreadId), latest)
            adoptAuthoritative(latest.thread)
            // 用服务端最新事实重建 diff 基准：下一次尝试读取新 cursor 与已回读的排队命令。
            baseRef.current = branchDraftFromThread(latest.thread)
            queuedCommandsRef.current = latest.queuedCommands
            boundThreadRef.current = latest.thread
            continue
          }
        }
        // 失败：保留用户选择为本地草稿（绝不丢输入），并暴露错误/冲突提示。
        const presentation = presentConflict(error)
        if (presentation != null) {
          setConflict(presentation)
          setSettingsError(null)
        } else {
          setConflict(null)
          setSettingsError(errorMessage(error))
        }
        return
      }
    }
  }

  /**
   * 用权威 Thread DTO 完整重载 base + draft（base === draft，干净）。用于
   * 接受外部刷新后的当前 Thread 快照（yolo 保留服务器值；settings 取自 DTO）。
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
    settingsStatus,
    settingsError,
    dismissSettingsError: () => setSettingsError(null),
    yoloError,
    dismissYoloError: () => setYoloError(null),
    conflict,
    dismissConflict: () => setConflict(null),
    selectAgent,
    setYoloEnabled,
    selectModel,
    selectEnvironment,
    resetDraftFromThread,
    buildBatch,
    buildGoalBatch,
  }
}
