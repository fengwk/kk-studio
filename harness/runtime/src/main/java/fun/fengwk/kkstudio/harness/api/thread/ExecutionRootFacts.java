package fun.fengwk.kkstudio.harness.api.thread;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * 按执行根汇总的持久执行事实，不是调度指令。
 *
 * @param rootThreadId 执行树根 Thread 的身份
 * @param waitingForHuman 任一节点已有 WAITING_APPROVAL 或 WAITING_INPUT 的工具调用
 * @param hasWork 任一节点有 RUNNING 的调用或有效租约、可领取 Work（含到期重试）
 * @param blocked 任一节点有等待就绪环境或未到期的 Work、未结算 Join 或未确认退出
 * @param stopped 执行根的持久化执行控制为 STOPPED
 * @param headOutcome 执行根实际 head 为 TURN_END 时的 outcome，否则为空
 * @param lastActivityAt 树内 Thread、模型调用或工具调用最近一次持久变更的时间
 */
public record ExecutionRootFacts(
    UUID rootThreadId,
    boolean waitingForHuman,
    boolean hasWork,
    boolean blocked,
    boolean stopped,
    TurnEndOutcome headOutcome,
    Instant lastActivityAt) {}
