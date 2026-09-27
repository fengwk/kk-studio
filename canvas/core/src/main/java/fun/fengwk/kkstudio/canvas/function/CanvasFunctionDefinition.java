package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.Objects;

/**
 * Adapter 对外声明的稳定 Canvas Function 定义：函数名、说明、严格 JSON args schema 与输入/输出限制。
 *
 * <p>{@code name} 是全局唯一的 canonical 小写 token（例如 {@code image.crop}），也是节点 {@code function.name}
 * 的唯一解析键。{@code argsSchema} 只描述业务输入，不要求函数具备 model、provider 或 prompt 参数；生成类函数把模型选择表达为普通参数。 引用输入由
 * {@code resourceReference} 属性声明，其数量与种类上限仍由 {@link CanvasFunctionReferencePolicy} 约束。
 */
public record CanvasFunctionDefinition(
    String name,
    String description,
    JsonObject argsSchema,
    CanvasResourceKind outputKind,
    CanvasFunctionReferencePolicy referencePolicy) {

  public CanvasFunctionDefinition {
    requireText(name, "name");
    if (!name.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*")) {
      throw new IllegalArgumentException("name must be a canonical lowercase function token");
    }
    requireText(description, "description");
    Objects.requireNonNull(argsSchema, "argsSchema");
    CanvasFunctionArgsSchema.validate(argsSchema);
    Objects.requireNonNull(outputKind, "outputKind");
    if (outputKind != CanvasResourceKind.IMAGE && outputKind != CanvasResourceKind.VIDEO) {
      throw new IllegalArgumentException("Canvas Function outputKind must be IMAGE or VIDEO");
    }
    Objects.requireNonNull(referencePolicy, "referencePolicy");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank without surrounding space");
    }
  }
}
