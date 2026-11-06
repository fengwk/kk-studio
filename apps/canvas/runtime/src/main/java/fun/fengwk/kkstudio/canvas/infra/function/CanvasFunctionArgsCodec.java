package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsSchema;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.Objects;

/** Function args 的唯一严格 parser 与 canonical encoder。 */
@Component
public final class CanvasFunctionArgsCodec {

  /** 严格解析并按定义校验、补齐默认值。 */
  public JsonObject decode(String argsJson, CanvasFunctionDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    if (argsJson == null || argsJson.isBlank()) {
      throw DomainException.validation("function.args", "function args must not be blank");
    }
    JsonObject args;
    try {
      args = CanvasJson.parseObject(argsJson);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          "function.args",
          "function args must be a strict JSON object: " + error.getMessage(),
          error);
    }
    try {
      return CanvasFunctionArgsSchema.normalize(args, definition.argsSchema(), "args");
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("function.args", error.getMessage(), error);
    }
  }

  /** canonical 编码已校验的 args。 */
  public String encode(JsonObject args) {
    return Objects.requireNonNull(args, "args").write();
  }
}
