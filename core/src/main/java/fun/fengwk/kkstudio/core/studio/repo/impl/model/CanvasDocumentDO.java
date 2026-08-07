package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code canvas_document} 行映射：canvas 聚合头。 */
@Data
public class CanvasDocumentDO {

  /** 业务主键（bigint，由 kk_studio_id_seq 生成，> 0）。 */
  private Long id;

  /** 标题，必填：非空白且不超过 256 字符。 */
  private String title;

  /** 业务版本：非负，命令 CAS（compare-and-swap）的依据，每次成功命令提交 +1。 */
  private Long revision;

  /** 默认视口 JSON（jsonb，必填）。 */
  private String homeViewportJson;

  /** 更新时间（映射 {@code updated_at} timestamptz，毫秒精度）；用于列表排序（updated_at desc, id desc）。 */
  private OffsetDateTime updateTime;
}
