package fun.fengwk.kkstudio.share.canvas;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas document 及其节点、分组与引用连线的完整读模型。
 *
 * <p>{@code references} 是从各节点 Function args 的保留引用值投影出的连线，不单独持久化，也不构成第二份可写边索引。
 */
@Data
public class CanvasSnapshotDTO {

  private CanvasDocumentDTO document;

  private List<CanvasResourceNodeDTO> nodes = new ArrayList<>();

  private List<CanvasGroupDTO> groups = new ArrayList<>();

  private List<CanvasReferenceDTO> references = new ArrayList<>();
}
