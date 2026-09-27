package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsSchema;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;

import java.util.Objects;

/** Function args 的唯一严格 parser 与 canonical encoder。 */
@Component
public final class CanvasFunctionArgsCodec implements CanvasFunctionArgsCodecPort {

  @Override
  public JsonObject decode(String argsJson, CanvasFunctionDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    if (argsJson == null || argsJson.isBlank()) {
      throw new IllegalArgumentException("function args must not be blank");
    }
    JsonObject args;
    try {
      args = CanvasJson.parseObject(argsJson);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "function args must be a strict JSON object: " + error.getMessage(), error);
    }
    return CanvasFunctionArgsSchema.normalize(args, definition.argsSchema(), "args");
  }

  @Override
  public String encode(JsonObject args) {
    return Objects.requireNonNull(args, "args").write();
  }
}
