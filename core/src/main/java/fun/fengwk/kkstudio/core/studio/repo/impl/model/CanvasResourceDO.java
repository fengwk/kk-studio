package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CanvasResourceDO {
  private Long id;
  private Long canvasId;
  private String kind;
  private String mediaType;
  private String name;
  private Long size;
  private String textContent;
  private String metadataJson;
  private OffsetDateTime createdAt;
  private Long nodeId;
  private Integer resourceIndex;
}
