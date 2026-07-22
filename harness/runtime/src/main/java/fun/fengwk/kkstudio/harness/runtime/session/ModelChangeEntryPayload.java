package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/**
 * SET_MODEL / SET_AGENT 应用后写入的 model/variant 配置变更 Entry。
 *
 * <p>路径上最后一次 {@code MODEL_CHANGE} 决定分支/回放时的 model/variant；Thread 字段是该 Thread 应用到 head 后的当前生效配置。
 */
public record ModelChangeEntryPayload(String modelId, String variant)
    implements SessionEntryPayload {
  public ModelChangeEntryPayload {
    modelId = Objects.requireNonNull(modelId, "modelId").trim();
    if (modelId.isEmpty()) {
      throw new IllegalArgumentException("modelId must not be blank");
    }
    variant = Objects.requireNonNull(variant, "variant").trim();
    if (variant.isEmpty()) {
      throw new IllegalArgumentException("variant must not be blank");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.MODEL_CHANGE;
  }
}
