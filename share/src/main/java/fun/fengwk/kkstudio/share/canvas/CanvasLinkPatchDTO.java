package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Canvas link 的 patch 项：完整 UPSERT 或按 {@code (sourceNodeId, targetNodeId)} REMOVE。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasLinkPatchDTO.Upsert.class, name = "UPSERT"),
  @JsonSubTypes.Type(value = CanvasLinkPatchDTO.Remove.class, name = "REMOVE")
})
public sealed interface CanvasLinkPatchDTO
    permits CanvasLinkPatchDTO.Upsert, CanvasLinkPatchDTO.Remove {

  record Upsert(CanvasLinkDTO link) implements CanvasLinkPatchDTO {}

  record Remove(String sourceNodeId, String targetNodeId) implements CanvasLinkPatchDTO {}
}
