package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/** Canvas node 的 patch 项：完整 UPSERT（含 resources/function/run 内嵌投影）或按 id REMOVE。 */
public sealed interface CanvasNodePatch permits CanvasNodePatch.Upsert, CanvasNodePatch.Remove {

  record Upsert(CanvasResourceNode node) implements CanvasNodePatch {
    public Upsert {
      Objects.requireNonNull(node, "node");
    }
  }

  record Remove(UUID nodeId) implements CanvasNodePatch {
    public Remove {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }
}
