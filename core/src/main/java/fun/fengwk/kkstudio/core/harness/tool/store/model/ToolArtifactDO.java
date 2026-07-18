package fun.fengwk.kkstudio.core.harness.tool.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code tool_artifact} 行映射：全局可寻址的不可变工具输出。 */
@Data
public class ToolArtifactDO {
  /** 全局主键（Snowflake）。 */
  private Long id;

  /** RFC media type。 */
  private String mediaType;

  /** 内容编码（如 identity / base64 语义由上层约定）。 */
  private String encoding;

  /** 完整不可变输出字节。 */
  private byte[] content;

  /** 内容字节数。 */
  private Long sizeBytes;

  /** 内容 SHA-256 摘要。 */
  private String sha256;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
