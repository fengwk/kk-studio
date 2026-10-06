package fun.fengwk.kkstudio.harness.runtime.model;

import java.util.Objects;
import java.util.Set;

/**
 * 当前 turn 所选 Model 的运行时描述。
 *
 * <p>{@code providerName} 与 {@code modelName} 是解析当前 Provider / Model 定义的 Catalog 逻辑标识（不可变资源身份，同时用于
 * branch settings 对照、prompt cache key 之外的逻辑引用与诊断）；{@code modelId} 是发往上游 Provider 的真实 wire 模型标识，两者
 * 相互独立且可以不相等。能力以非空 {@code inputModalities} 集合与 {@code tools} / {@code reasoning} 两个 primitive
 * 布尔直接表达。 本 turn 选中的有效 variant 与输出预算存放在 ephemeral 执行值与 durable invocation request 中，与该 descriptor
 * 并列。
 *
 * <p>Provider 类型与 prompt-cache 留存档位不随本 descriptor 冻结，由调用方按需从当前 ProviderFactory
 * 解析并显式传入；成本（pricing）不属于执行期描述，读取投影阶段按当前 catalog 价格计算。
 */
public record ModelDescriptor(
    String providerName,
    String modelName,
    String modelId,
    Set<ModelInputModality> inputModalities,
    boolean tools,
    boolean reasoning) {

  public ModelDescriptor {
    providerName = requireName(providerName, "providerName");
    modelName = requireName(modelName, "modelName");
    modelId = requireName(modelId, "modelId");
    inputModalities = requireInputModalities(inputModalities);
  }

  private static Set<ModelInputModality> requireInputModalities(
      Set<ModelInputModality> inputModalities) {
    Objects.requireNonNull(inputModalities, "inputModalities");
    if (inputModalities.isEmpty()) {
      throw new IllegalArgumentException("inputModalities must not be empty");
    }
    return Set.copyOf(inputModalities);
  }

  private static String requireName(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
    }
    if (name.equals("providerName") && value.indexOf('/') >= 0) {
      throw new IllegalArgumentException(name + " must not contain '/'");
    }
    return value;
  }
}
