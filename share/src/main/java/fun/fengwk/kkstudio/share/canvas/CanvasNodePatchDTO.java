package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Canvas node 的 patch 项：完整 UPSERT（含 resources/function/run 内嵌投影）或按 id REMOVE。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasNodePatchDTO.Upsert.class, name = "UPSERT"),
  @JsonSubTypes.Type(value = CanvasNodePatchDTO.Remove.class, name = "REMOVE")
})
public sealed interface CanvasNodePatchDTO
    permits CanvasNodePatchDTO.Upsert, CanvasNodePatchDTO.Remove {

  record Upsert(CanvasResourceNodeDTO node) implements CanvasNodePatchDTO {}

  record Remove(String nodeId) implements CanvasNodePatchDTO {}
}
