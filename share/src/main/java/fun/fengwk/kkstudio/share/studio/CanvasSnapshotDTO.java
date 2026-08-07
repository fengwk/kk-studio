package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasSnapshotDTO {
  /** 画布文档（含 id/title/revision/homeViewportJson）。 */
  private CanvasDocumentDTO document;

  /** 画布节点列表（默认空列表）。 */
  private List<CanvasNodeDTO> nodes = new ArrayList<>();

  /** 画布链接列表（默认空列表）。 */
  private List<CanvasLinkDTO> links = new ArrayList<>();
}
