package fun.fengwk.kkstudio.web.studio;

import fun.fengwk.kkstudio.share.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.studio.CanvasLinkDTO;
import fun.fengwk.kkstudio.share.studio.CanvasNodeDTO;
import fun.fengwk.kkstudio.share.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;

import java.util.stream.Collectors;

/** Web-layer mapper from Canvas domain objects to share DTOs. */
public final class StudioWebMapper {

  private StudioWebMapper() {}

  public static CanvasDocumentDTO toDto(CanvasDocument document) {
    CanvasDocumentDTO dto = new CanvasDocumentDTO();
    dto.setId(Long.toString(document.id()));
    dto.setTitle(document.title());
    dto.setRevision(Long.toString(document.revision()));
    dto.setHomeViewportJson(document.homeViewportJson());
    return dto;
  }

  public static CanvasSnapshotDTO toDto(CanvasSnapshot snapshot) {
    CanvasSnapshotDTO dto = new CanvasSnapshotDTO();
    dto.setDocument(toDto(snapshot.document()));
    dto.setNodes(
        snapshot.nodes().stream().map(StudioWebMapper::toDto).collect(Collectors.toList()));
    dto.setLinks(
        snapshot.links().stream().map(StudioWebMapper::toDto).collect(Collectors.toList()));
    return dto;
  }

  public static CanvasNodeDTO toDto(CanvasNode node) {
    CanvasNodeDTO dto = new CanvasNodeDTO();
    dto.setId(Long.toString(node.id()));
    dto.setKind(node.kind().name());
    dto.setNodeType(node.nodeType());
    dto.setName(node.name());
    dto.setX(node.transform().x());
    dto.setY(node.transform().y());
    dto.setWidth(node.transform().width());
    dto.setHeight(node.transform().height());
    dto.setDataJson(node.dataJson());
    return dto;
  }

  public static CanvasLinkDTO toDto(CanvasLink link) {
    CanvasLinkDTO dto = new CanvasLinkDTO();
    dto.setId(Long.toString(link.id()));
    dto.setSourceNodeId(Long.toString(link.sourceNodeId()));
    dto.setTargetNodeId(Long.toString(link.targetNodeId()));
    return dto;
  }
}
