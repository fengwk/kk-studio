package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model response turn 开始处的不可变 durable 边界。
 *
 * <p>{@code ownerThreadId} 始终记录创建该 turn 的 Thread。{@code contextWindow} 与 {@code maxOutputTokens} 仅在
 * Resolver 成功时一起为正整数冻结，二者要么同为 null（rejected / 候补）要么同为非 null； {@code compaction} 非空当且仅当 {@code
 * reason == COMPACTION}，冻结本次压缩的最小元数据。
 *
 * <p>{@code settings} 始终是 Thread 自己的设置，后续 turn 由它继承，因此模型失效时也不改写。{@code fallbackModel} 只在 Resolver
 * 发现 {@code settings.model()} 不可用、改用 Agent 默认模型执行时非空，记录本 turn 实际调用的型号，用于界面提示与按实际型号计价。
 *
 * <p>{@code capabilities} 在 Resolver 成功、Turn 与 ModelInvocation 同事务提交时冻结；pending / rejected
 * TurnStart 允许为 null。工具执行、审批、{@code task} 父权限与媒体读取只读取该快照，不再沿 ToolInvocation 反查 Model。
 */
public record TurnStartPayload(
    TurnStartReason reason,
    BranchSettings settings,
    UUID ownerThreadId,
    Integer contextWindow,
    Integer maxOutputTokens,
    CompactionStart compaction,
    ModelSelection fallbackModel,
    TurnCapabilities capabilities)
    implements EntryPayload {

  /** 候补 path / rejected turn：contextWindow 与 maxOutputTokens 尚未补齐，无压缩元数据与冻结能力。 */
  public TurnStartPayload(TurnStartReason reason, BranchSettings settings, UUID ownerThreadId) {
    this(reason, settings, ownerThreadId, null, null, null, null, null);
  }

  /** 尚未冻结公共能力的已解析形状（Stop / Compaction barrier 等不执行工具的 Turn）。 */
  public TurnStartPayload(
      TurnStartReason reason,
      BranchSettings settings,
      UUID ownerThreadId,
      Integer contextWindow,
      Integer maxOutputTokens,
      CompactionStart compaction,
      ModelSelection fallbackModel) {
    this(
        reason,
        settings,
        ownerThreadId,
        contextWindow,
        maxOutputTokens,
        compaction,
        fallbackModel,
        null);
  }

  public TurnStartPayload {
    reason = Objects.requireNonNull(reason, "reason");
    settings = Objects.requireNonNull(settings, "settings");
    ownerThreadId = Objects.requireNonNull(ownerThreadId, "ownerThreadId");
    if ((contextWindow == null) != (maxOutputTokens == null)) {
      throw new IllegalArgumentException(
          "contextWindow and maxOutputTokens must be resolved or rejected together");
    }
    if (contextWindow != null && contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive when present");
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive when present");
    }
    if ((reason == TurnStartReason.COMPACTION) != (compaction != null)) {
      throw new IllegalArgumentException(
          "compaction metadata is required iff reason is COMPACTION");
    }
    if (fallbackModel != null
        && (contextWindow == null
            || compaction != null
            || fallbackModel.equals(settings.model()))) {
      throw new IllegalArgumentException(
          "fallbackModel is only recorded for a resolved non-compaction turn that left the thread"
              + " model");
    }
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_START;
  }
}
