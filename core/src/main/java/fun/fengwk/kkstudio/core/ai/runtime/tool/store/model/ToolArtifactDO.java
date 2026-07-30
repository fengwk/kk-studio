package fun.fengwk.kkstudio.core.ai.runtime.tool.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code harness_artifact} 行映射：全局可寻址的不可变工具输出。 */
@Data
public class ToolArtifactDO {
  /** PostgreSQL sequence 主键。 */
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

  /** 创建时间。 */
  private OffsetDateTime createdAt;
}
