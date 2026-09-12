package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;

import java.util.Objects;

/**
 * Resolved 请求与 candidate branch 事实的机械一致性校验（Harness 边界）。
 *
 * <p>可直接对照 candidate {@link EntryPath} 最新 {@link BranchSettings} 的字段只有 provider/model/variant 选择（压缩
 * fallback 对照 preparation 的 executionModel），以及 environment-required tool/skill 的 environment。YOLO
 * 不进入 spec，也不参与校验。
 *
 * <p>压缩 turn 必须零 tool/skill binding，model/variant 等于 {@code preparation.executionModel()}，且
 * Resolved 的 model/variant 必须匹配 executionModel；实际 contextWindow / maxOutputTokens 由该 execution
 * model 的 Resolver 结果冻结，不能与 primary model 的规划值比较。正常 turn 的 basis path 末尾不得带 COMPACTION
 * TURN_START。任何不一致都是 Resolver 契约 / 编程错误：抛清晰的 {@link IllegalStateException}。
 */
public final class ResolvedRequestValidator {

  private ResolvedRequestValidator() {}

  public static void validate(
      EntryPath candidatePath, CompactionPreparation preparation, TurnResolver.Resolved resolved) {
    Objects.requireNonNull(candidatePath, "candidatePath");
    Objects.requireNonNull(resolved, "resolved");
    ModelRequestSpec spec = resolved.spec();
    BranchSettings settings = candidatePath.baseSettings();
    if (preparation == null) {
      if (ModelRequestMaterializer.compactionStartAtHead(candidatePath) != null) {
        throw new IllegalStateException(
            "a normal turn must not carry compaction TURN_START metadata");
      }
    } else {
      if (!preparation
          .frozenStart()
          .equals(ModelRequestMaterializer.compactionStartAtHead(candidatePath))) {
        throw new IllegalStateException(
            "a compaction candidate TURN_START must carry the exact frozen preparation metadata");
      }
      if (!spec.preambleMessages().isEmpty()
          || !spec.toolBindings().isEmpty()
          || !spec.skillBindings().isEmpty()
          || !spec.subagentBindings().isEmpty()
          || !spec.cacheControl().equals(ProviderCacheControl.none())) {
        throw new IllegalStateException(
            "compaction requests must carry only model/variant with cache disabled");
      }
    }
    ModelSelection expectedModel =
        preparation == null ? settings.model() : preparation.executionModel();
    // BranchSettings 只持久化 agent/model 选择；environmentId 由 Agent definition 每轮解析并冻结进 binding，
    // binding 之间的一致性（同一环境、skill source 与该环境一致）由 ModelRequestSpec 构造边界保证。
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
