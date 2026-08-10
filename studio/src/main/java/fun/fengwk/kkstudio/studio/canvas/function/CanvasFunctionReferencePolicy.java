package fun.fengwk.kkstudio.studio.canvas.function;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canvas Function 对冻结引用 manifest 的通用限制。 */
public record CanvasFunctionReferencePolicy(
    Set<CanvasResourceKind> allowedKinds,
    int maxReferences,
    Map<CanvasResourceKind, Integer> maxByKind) {

  public CanvasFunctionReferencePolicy {
    Objects.requireNonNull(allowedKinds, "allowedKinds");
    allowedKinds = Set.copyOf(allowedKinds);
    if (allowedKinds.isEmpty() || allowedKinds.contains(CanvasResourceKind.TEXT)) {
      throw new IllegalArgumentException("allowedKinds must contain non-TEXT kinds");
    }
    if (maxReferences < 0) {
      throw new IllegalArgumentException("maxReferences must be >= 0");
    }
    Objects.requireNonNull(maxByKind, "maxByKind");
    maxByKind = Map.copyOf(maxByKind);
    for (Map.Entry<CanvasResourceKind, Integer> entry : maxByKind.entrySet()) {
      if (!allowedKinds.contains(entry.getKey())
          || entry.getValue() == null
          || entry.getValue() < 0
          || entry.getValue() > maxReferences) {
        throw new IllegalArgumentException(
            "maxByKind must be bounded by allowedKinds/maxReferences");
      }
    }
  }

  public int maxFor(CanvasResourceKind kind) {
    return maxByKind.getOrDefault(kind, maxReferences);
  }
}
