package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code canvas_document} row mapping: canvas aggregate head. */
@Data
public class CanvasDocumentDO {
  /** Business id. */
  private Long id;

  /** Title. */
  private String title;

  /** Business revision used by command CAS. */
  private Long revision;

  /** Default viewport JSON. */
  private String homeViewportJson;

  /** Updated time (maps {@code updated_at} timestamptz); used for list ordering. */
  private OffsetDateTime updateTime;
}
