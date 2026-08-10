package fun.fengwk.kkstudio.studio.canvas.function;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adapter 每次执行或恢复时读取的完整冻结计划与 checkpoint。 */
public record CanvasFunctionFrozenRun(
    long canvasId,
    long nodeId,
    String nodeName,
    String requestId,
    CanvasFunctionModel model,
    CanvasFunctionConfig config,
    List<CanvasFunctionFrozenReference> manifest,
    String outputName,
    long targetResourceId,
    String stage,
    Map<String, Object> adapterState) {

  public CanvasFunctionFrozenRun {
    if (canvasId <= 0L || nodeId <= 0L || targetResourceId <= 0L) {
      throw new IllegalArgumentException("canvasId/nodeId/targetResourceId must be > 0");
    }
    requireText(nodeName, "nodeName");
    requireText(requestId, "requestId");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(config, "config");
    manifest = List.copyOf(Objects.requireNonNull(manifest, "manifest"));
    requireText(outputName, "outputName");
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
