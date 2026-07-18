package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code canvas_document} 行映射：画布文档头。 */
@Data
public class CanvasDocumentDO {
  /** 业务主键。 */
  private Long id;

  /** 所属工作区。 */
  private Long workspaceId;

  /** 标题。 */
  private String title;

  /** 文档 schema 版本。 */
  private Integer schemaVersion;

  /** 业务 revision（CAS 用）。 */
  private Long revision;

  /** 生命周期状态。 */
  private String lifecycle;

  /** 默认视口 JSON。 */
  private String homeViewportJson;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
