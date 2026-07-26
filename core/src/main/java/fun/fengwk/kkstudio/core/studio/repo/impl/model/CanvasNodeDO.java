package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/** {@code canvas_node} row mapping: canvas node. */
@Data
public class CanvasNodeDO {
  /** Business id. */
  private Long id;

  /** Owning canvas id. */
  private Long canvasId;

  /** Domain kind: RESOURCE / FUNCTION. */
  private String kind;

  /** Node type name. */
  private String nodeType;

  /** Display name. */
  private String name;

  /** Canvas x. */
  private Double x;

  /** Canvas y. */
  private Double y;

  /** Width. */
  private Double width;

  /** Height. */
  private Double height;

  /** Subtype payload JSON. */
  private String dataJson;
}
