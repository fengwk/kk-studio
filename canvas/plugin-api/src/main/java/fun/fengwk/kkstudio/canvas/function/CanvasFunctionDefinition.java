package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter 对外声明的稳定 Canvas Function 定义：函数名、说明、严格 JSON args schema、输出计划与输入限制。
 *
 * <p>{@code name} 是全局唯一的 canonical 小写 token（例如 {@code image.crop}），也是节点 {@code function.name}
 * 的唯一解析键。{@code argsSchema} 只描述业务输入，不要求函数具备 model、provider 或 prompt 参数；生成类函数把模型选择表达为普通参数。 引用输入由
 * {@code resourceReference} 属性声明，其数量与种类上限仍由 {@link CanvasFunctionReferencePolicy} 约束。
 *
 * <p>{@code outputs} 是有界的输出计划：启动时按槽位预分配 Resource ID 并写入 Run checkpoint，成功发布时按该顺序原子挂接。槽位类型可以是 {@code
 * TEXT}（内联文本）或媒体，因此一个函数可以同时产出文本与媒体。
 */
public record CanvasFunctionDefinition(
    String name,
    String description,
    JsonObject argsSchema,
    List<CanvasFunctionOutputSpec> outputs,
    CanvasFunctionReferencePolicy referencePolicy) {

  public CanvasFunctionDefinition {
    requireText(name, "name");
    if (!name.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*")) {
      throw new IllegalArgumentException("name must be a canonical lowercase function token");
    }
    requireText(description, "description");
    Objects.requireNonNull(argsSchema, "argsSchema");
    CanvasFunctionArgsSchema.validate(argsSchema);
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("outputs must declare at least one slot");
    }
    if (outputs.size() > CanvasFunctionOutputSpec.MAX_OUTPUTS) {
      throw new IllegalArgumentException(
          "outputs must not exceed " + CanvasFunctionOutputSpec.MAX_OUTPUTS + " slots");
    }
    Set<String> explicitNames = new HashSet<>();
    for (CanvasFunctionOutputSpec output : outputs) {
      Objects.requireNonNull(output, "outputs contains null");
      if (output.name() != null && !explicitNames.add(output.name())) {
        throw new IllegalArgumentException("outputs must not declare duplicate names");
      }
    }
    Objects.requireNonNull(referencePolicy, "referencePolicy");
  }

  public static CanvasFunctionDefinition of(
      String name,
      String description,
      JsonObject argsSchema,
      CanvasResourceKind outputKind,
      CanvasFunctionReferencePolicy referencePolicy) {
    return new CanvasFunctionDefinition(
        name,
        description,
        argsSchema,
        List.of(CanvasFunctionOutputSpec.of(outputKind)),
        referencePolicy);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank without surrounding space");
    }
  }
}
