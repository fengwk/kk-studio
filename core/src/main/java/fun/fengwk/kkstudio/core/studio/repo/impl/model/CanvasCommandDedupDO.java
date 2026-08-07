package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/**
 * {@code canvas_command_dedup} 行映射：以 {@code (canvas_id, command_id)} 为键的纯幂等事实。 {@code request_hash}
 * 是服务端计算的规范化命令负载的 SHA-256。
 */
@Data
public class CanvasCommandDedupDO {

  /** 所属 canvas 文档 id（复合主键一部分，外键引用 canvas_document.id，级联删除，> 0）。 */
  private Long canvasId;

  /** 客户端幂等键（复合主键一部分，varchar(128)，非空白）。 */
  private String commandId;

  /** 服务端计算的命令规范化负载 SHA-256 十六进制摘要（varchar(64)，固定 64 字符）。 */
  private String requestHash;
}
