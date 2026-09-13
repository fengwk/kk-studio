package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BLOB 节点元数据公开表示。
 *
 * <p>提供 blobId、mediaType、sizeBytes（规范非负十进制字符串）、sha256 与创建时间。 若媒体类型为 UTF-8 文本或 Tool
 * Artifact，可携带行窗口表示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudBlobMetadataDTO {

  /** 关联的 storage_blob UUID。 */
  private String blobId;

  /** 媒体类型。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String mediaType;

  /** Blob 字节数（规范非负十进制字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sizeBytes;

  /** Blob SHA-256 摘要。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sha256;

  /** 创建时间（ISO-8601 字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String createdAt;

  /** 可流式解码的 UTF-8 文本/Artifact 行窗口；非文本媒体时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private CloudTextWindowDTO text;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
