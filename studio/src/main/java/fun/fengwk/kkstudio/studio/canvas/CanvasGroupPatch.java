package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;
import java.util.UUID;

/** Canvas group 的 patch 项：完整 UPSERT 或按 id REMOVE。 */
public sealed interface CanvasGroupPatch permits CanvasGroupPatch.Upsert, CanvasGroupPatch.Remove {

  record Upsert(CanvasGroup group) implements CanvasGroupPatch {
    public Upsert {
      Objects.requireNonNull(group, "group");
    }
  }

  record Remove(UUID groupId) implements CanvasGroupPatch {
    public Remove {
      Objects.requireNonNull(groupId, "groupId");
    }
  }
}
