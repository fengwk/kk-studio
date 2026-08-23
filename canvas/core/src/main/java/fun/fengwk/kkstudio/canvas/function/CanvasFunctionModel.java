package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Adapter 对外声明的稳定 Canvas Function 模型能力。 */
public record CanvasFunctionModel(
    String key,
    String label,
    CanvasResourceKind outputKind,
    CanvasFunctionReferencePolicy referencePolicy,
    List<CanvasFunctionParameterDefinition> parameters) {

  public CanvasFunctionModel {
    requireText(key, "key");
    if (!key.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*")) {
      throw new IllegalArgumentException("key must be a canonical lowercase model token");
    }
    requireText(label, "label");
    Objects.requireNonNull(outputKind, "outputKind");
    if (outputKind == CanvasResourceKind.TEXT || outputKind == CanvasResourceKind.AUDIO) {
      throw new IllegalArgumentException("Canvas Function v1 outputKind must be IMAGE or VIDEO");
    }
    Objects.requireNonNull(referencePolicy, "referencePolicy");
    parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    Set<String> keys = new HashSet<>();
    for (CanvasFunctionParameterDefinition parameter : parameters) {
      if (!keys.add(parameter.key())) {
        throw new IllegalArgumentException("duplicate parameter key: " + parameter.key());
      }
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank without surrounding space");
    }
  }
}
