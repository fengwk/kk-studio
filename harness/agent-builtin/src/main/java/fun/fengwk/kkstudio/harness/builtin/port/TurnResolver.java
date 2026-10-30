package fun.fengwk.kkstudio.harness.builtin.port;

import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.util.Objects;
import java.util.UUID;

/**
 * 同步、无副作用、调用方事务内的 turn 解析端口：把调用方传入的 candidate {@link EntryPath} 解析为冻结的 {@link ModelRequestSpec} 与本次
 * turn 的 {@code contextWindow} / {@code maxOutputTokens}。
 *
 * <p>调用方在同一个短事务内锁 Session / Thread、捕获 queued Command 快照、分配稳定 Entry ID、构造 candidate path，并直接调用本端口；
 * Resolver 不额外开启事务，也不要求调用方传入事务句柄（Spring 的既有事务传播会 join 当前物理事务）。candidate path 是 Thread 当前 head
 * 的已持久化前缀与尚未持久化的计划 suffix（normalization / TURN_START / Message）组成的完整 root-to-head 链，已通过 EntryPath
 * 全量不变量校验。实现必须只读取最新 catalog / environment 等同库事实：不得写 store、不得产生副作用、不得发起外部 I/O、不得要求事务上下文，且不得按
 * candidate Entry ID 回查 Store（suffix 尚未持久化）。其解析结果在同一事务内直接成为 durable execution fact；不存在第二次 CAS。只有显式
 * typed 的 {@link TurnResolveTransientException}（临时基础设施失败，DB / 网络不可用）会被 Processor 在事务整体回滚后
 * reschedule；任何其它 RuntimeException、null 结果或 validator 契约失败都视为确定性失败，落成 durable AssistantError +
 * FAILED TURN_END 并结算 Join，绝不无限重排。
 *
 * <p>{@code compactionPreparation} 非空当且仅当本次是 COMPACTION turn：实现必须按切分事实冻结输出预算作为 {@code
 * maxOutputTokens}，不得把 messagesToSummarize / previousSummary 写入 spec。YOLO 不进入本端口。
 */
public interface TurnResolver {

  /** 解析一次 turn；临时基础设施失败必须抛出 {@link TurnResolveTransientException}，由调用方 reschedule。 */
  Result resolve(UUID threadId, EntryPath path, CompactionPreparation compactionPreparation);

  /** 解析结果：冻结请求或确定性拒绝。 */
  sealed interface Result
      permits TurnResolver.Resolved, TurnResolver.Rejected, TurnResolver.CompactionResolved {}

  /** 解析成功：紧凑 spec 已冻结，contextWindow 与 maxOutputTokens 为本次 turn 冻结的正整数。 */
  record Resolved(ModelRequestSpec spec, int contextWindow, int maxOutputTokens) implements Result {
    public Resolved {
      spec = Objects.requireNonNull(spec, "spec");
      if (contextWindow <= 0) {
        throw new IllegalArgumentException("contextWindow must be positive");
      }
      if (maxOutputTokens <= 0) {
        throw new IllegalArgumentException("maxOutputTokens must be positive");
      }
    }
  }

  /** 确定性拒绝：写入 durable AssistantError barrier，不产生 ModelInvocation。 */
  record Rejected(AssistantError error) implements Result {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 压缩 turn 解析成功：冻结 compactor 执行模型、输出预算与子 Thread 设置。 */
  record CompactionResolved(
      ModelSelection executionModel,
      long outputBudget,
      int contextWindow,
      int maxOutputTokens,
      BranchSettings childSettings)
      implements Result {
    public CompactionResolved {
      executionModel = Objects.requireNonNull(executionModel, "executionModel");
      if (outputBudget <= 0L) {
        throw new IllegalArgumentException("outputBudget must be positive");
      }
      if (contextWindow <= 0) {
        throw new IllegalArgumentException("contextWindow must be positive");
      }
      if (maxOutputTokens <= 0) {
        throw new IllegalArgumentException("maxOutputTokens must be positive");
      }
      childSettings = Objects.requireNonNull(childSettings, "childSettings");
    }
  }
}
