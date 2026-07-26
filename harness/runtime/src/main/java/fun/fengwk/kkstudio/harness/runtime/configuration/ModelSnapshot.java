package fun.fengwk.kkstudio.harness.runtime.configuration;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;

import java.util.Objects;

/**
 * 冻结的 Model 与 effective variant 快照。
 *
 * <p>仅做非空校验。{@code variant} 必须已经过 live resolver 的未知 variant 拒绝流程；descriptor 不再承担 variants 列表，因此本
 * snapshot 不在构造期重复校验 variant 身份。
 */
public record ModelSnapshot(ModelDescriptor descriptor, ModelVariant variant) {

  public ModelSnapshot {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    variant = Objects.requireNonNull(variant, "variant");
  }
}
