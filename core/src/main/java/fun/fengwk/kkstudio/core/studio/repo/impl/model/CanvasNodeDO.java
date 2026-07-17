package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CanvasNodeDO {
  private Long id;
  private Long canvasId;
  private String kind;
  private String nodeType;
  private Integer nodeTypeVersion;
  private String name;
  private Long parentGroupId;
  private Double x;
  private Double y;
  private Double width;
  private Double height;
  private Double rotation;
  private Long zIndex;
  private Boolean locked;
  private Boolean hidden;
  private String validity;
  private String dataJson;
  private Long revision;
  private LocalDateTime deletedTime;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
