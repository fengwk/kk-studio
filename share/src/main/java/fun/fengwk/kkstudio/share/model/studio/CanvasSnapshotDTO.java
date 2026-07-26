package fun.fengwk.kkstudio.share.model.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasSnapshotDTO {
  private CanvasDocumentDTO document;
  private List<CanvasNodeDTO> nodes = new ArrayList<>();
  private List<CanvasLinkDTO> links = new ArrayList<>();
}
