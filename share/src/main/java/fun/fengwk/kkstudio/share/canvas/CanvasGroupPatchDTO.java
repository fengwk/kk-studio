package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Canvas group 的 patch 项：完整 UPSERT 或按 id REMOVE。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasGroupPatchDTO.Upsert.class, name = "UPSERT"),
  @JsonSubTypes.Type(value = CanvasGroupPatchDTO.Remove.class, name = "REMOVE")
})
public sealed interface CanvasGroupPatchDTO
    permits CanvasGroupPatchDTO.Upsert, CanvasGroupPatchDTO.Remove {

  record Upsert(CanvasGroupDTO group) implements CanvasGroupPatchDTO {}

  record Remove(String groupId) implements CanvasGroupPatchDTO {}
}
