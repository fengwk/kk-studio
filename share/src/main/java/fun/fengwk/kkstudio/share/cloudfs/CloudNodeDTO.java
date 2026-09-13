package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cloud File System 节点公开表示。
 *
 * <p>提供 node id、规范虚拟绝对路径、名称、类型以及元数据 CAS 版本（规范非负十进制字符串）。 根据节点类型，附加暴露
 * blobId、mediaType、sizeBytes、sha256、revision 与时间戳事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudNodeDTO {

  /** 节点 UUID；虚拟根节点 {@code /} 时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String id;

  /** 规范虚拟绝对路径，例如 {@code /knowledge/doc.md}。 */
  private String path;

  /** 节点分段名称；虚拟根节点为空字符串。 */
  private String name;

  /** 节点类型：DIRECTORY, TEXT, BLOB。 */
  private String kind;

  /** 节点元数据 CAS 乐观锁版本（规范非负十进制字符串）。 */
  private String version;

  /** 关联的 storage_blob UUID（仅 BLOB 类型非空）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String blobId;

  /** 媒体类型；TEXT/BLOB 类型有效。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String mediaType;

  /** 内容 UTF-8 字节数或 Blob 字节数（规范非负十进制字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sizeBytes;

  /** 内容 SHA-256 摘要；TEXT/BLOB 类型有效。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String sha256;

  /** 文本节点的当前版本号（仅 TEXT 类型非空，规范非负十进制字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String revision;

  /** 创建时间（ISO-8601 字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String createdAt;

  /** 最后更新时间（ISO-8601 字符串）。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String updatedAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
