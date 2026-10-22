package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.util.Objects;
import java.util.UUID;

/**
 * 模型组某成员在逻辑调用开始时冻结的执行身份。
 *
 * <p>Provider 类型、连接代际、真实模型 descriptor 与（已解析的）Variant 全部随组版本一起冻结；成员切换只允许使用这里的快照，绝不重新读取 Catalog
 * 混入新配置。
 */
public record ModelGroupMemberExecution(
    ProviderType providerType,
    UUID providerConnectionGenerationId,
    ModelDescriptor model,
    ModelVariant variant) {

  public ModelGroupMemberExecution {
    providerType = Objects.requireNonNull(providerType, "providerType");
    providerConnectionGenerationId =
        Objects.requireNonNull(providerConnectionGenerationId, "providerConnectionGenerationId");
    model = Objects.requireNonNull(model, "model");
    variant = Objects.requireNonNull(variant, "variant");
  }
}
