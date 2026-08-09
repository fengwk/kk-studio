package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CanvasSnapshotDTO {
  private CanvasDocumentDTO document;
  private List<CanvasResourceNodeDTO> nodes = new ArrayList<>();
  private List<CanvasGroupDTO> groups = new ArrayList<>();
  private List<CanvasLinkDTO> links = new ArrayList<>();
}
