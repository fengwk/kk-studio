package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/** Canvas link 的 patch 项：完整 UPSERT 或按 {@code (sourceNodeId, targetNodeId)} REMOVE。 */
public sealed interface CanvasLinkPatch permits CanvasLinkPatch.Upsert, CanvasLinkPatch.Remove {

  record Upsert(CanvasLink link) implements CanvasLinkPatch {
    public Upsert {
      Objects.requireNonNull(link, "link");
    }
  }

  record Remove(UUID sourceNodeId, UUID targetNodeId) implements CanvasLinkPatch {
    public Remove {
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(targetNodeId, "targetNodeId");
    }
  }
}
