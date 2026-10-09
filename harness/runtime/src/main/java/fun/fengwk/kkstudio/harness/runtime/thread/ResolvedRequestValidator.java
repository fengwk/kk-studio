package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;

import java.util.Objects;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验（Harness 边界）。
 *
 * <p>可直接对照 candidate {@link EntryPath} 最新 {@link BranchSettings} 的字段只有 provider/model/variant
 * 选择。YOLO 不进入 spec，也不参与校验。
 *
 * <p>正常 turn 的 candidate path 末尾不得是 COMPACTION TURN_START：压缩 turn 只能解析为 {@link
 * TurnResolver.CompactionResolved} 并在隔离的 child Runtime Thread 内执行，绝不在父 Thread 直接调用模型。任何不一致都是
 * Resolver 契约 / 编程错误：抛清晰的 {@link IllegalStateException}。
 */
public final class ResolvedRequestValidator {

  private ResolvedRequestValidator() {}

  public static void validate(EntryPath candidatePath, TurnResolver.Resolved resolved) {
    Objects.requireNonNull(candidatePath, "candidatePath");
    Objects.requireNonNull(resolved, "resolved");
    ModelRequestSpec spec = resolved.spec();
    BranchSettings settings = candidatePath.baseSettings();
    Entry head = candidatePath.head();
    if (head.payload() instanceof TurnStartPayload start
        && start.reason() == TurnStartReason.COMPACTION) {
      throw new IllegalStateException("a normal turn must not carry a COMPACTION TURN_START");
    }
    ModelSelection expectedModel = settings.model();
    // BranchSettings 只持久化 agent/model 选择与可空 environmentName；Environment 每轮按 name 解析并冻结进
    // binding，binding 之间的一致性（同一环境、skill source 与该环境一致）由 ModelRequestSpec 构造边界保证。
    if (!spec.model().providerName().equals(expectedModel.providerName())
        || !spec.model().modelName().equals(expectedModel.modelName())) {
      throw new IllegalStateException(
          "resolved request model "
              + spec.model().providerName()
              + "/"
              + spec.model().modelName()
              + " does not match expected model "
              + expectedModel.providerName()
              + "/"
              + expectedModel.modelName());
    }
    if (!spec.variant().id().equals(expectedModel.variant())) {
      throw new IllegalStateException(
          "resolved request variant "
              + spec.variant().id()
              + " does not match expected variant "
              + expectedModel.variant());
    }
  }
}
