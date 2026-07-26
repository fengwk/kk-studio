package fun.fengwk.kkstudio.harness.runtime.configuration;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;

import java.util.Objects;

/**
 * 冻结的 Model 与 effective variant 快照。
 *
 * <p>{@code variant} 必须与 {@code descriptor.variants()} 中某项完全相等（{@link ModelVariant#equals}），否则
 * 视为非法配置组合并拒绝；其它字段仅做非空校验，避免重复构造可变结构。
 */
public record ModelSnapshot(ModelDescriptor descriptor, ModelVariant variant) {

  public ModelSnapshot {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    variant = Objects.requireNonNull(variant, "variant");
    if (descriptor.variants().stream().noneMatch(variant::equals)) {
      throw new IllegalArgumentException("variant must equal one of descriptor.variants() entries");
    }
  }
}
