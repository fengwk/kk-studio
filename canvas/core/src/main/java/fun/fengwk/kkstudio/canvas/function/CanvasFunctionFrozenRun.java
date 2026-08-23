package fun.fengwk.kkstudio.canvas.function;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Adapter 每次执行或恢复时读取的完整冻结计划与 checkpoint。 */
public record CanvasFunctionFrozenRun(
    UUID canvasId,
    UUID nodeId,
    String nodeName,
    UUID requestId,
    CanvasFunctionModel model,
    CanvasFunctionConfig config,
    List<CanvasFunctionFrozenReference> manifest,
    String outputName,
    UUID targetResourceId,
    String stage,
    Map<String, Object> adapterState) {

  public CanvasFunctionFrozenRun {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    requireText(nodeName, "nodeName");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(config, "config");
    manifest = List.copyOf(Objects.requireNonNull(manifest, "manifest"));
    requireText(outputName, "outputName");
    Objects.requireNonNull(targetResourceId, "targetResourceId");
    requireText(stage, "stage");
    adapterState =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(adapterState, "adapterState")));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
