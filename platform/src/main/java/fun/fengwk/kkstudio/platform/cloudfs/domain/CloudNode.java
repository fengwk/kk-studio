package fun.fengwk.kkstudio.platform.cloudfs.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Cloud File System 节点领域对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudNode {

  /** 节点唯一 UUID。 */
  private UUID id;

  /** 父节点 UUID；为 null 表示根目录 {@code /} 下的顶层节点。 */
  private UUID parentId;

  /** 节点分段名称。 */
  private String name;

  /** 节点类型：DIRECTORY, TEXT, BLOB。 */
  private CloudNodeKind kind;

  /** 元数据 CAS 乐观锁版本。 */
  private long version;

  /** 关联的 storage_blob UUID（仅 BLOB 类型非空）。 */
  private UUID blobId;

  /** 创建时间。 */
  private Instant createdAt;

  /** 最后更新时间。 */
  private Instant updatedAt;

  /** 判断当前节点是否为目录。 */
  public boolean isDirectory() {
    return kind == CloudNodeKind.DIRECTORY;
  }

  /** 判断当前节点是否为可编辑文本。 */
  public boolean isText() {
    return kind == CloudNodeKind.TEXT;
  }

  /** 判断当前节点是否为 BLOB。 */
  public boolean isBlob() {
    return kind == CloudNodeKind.BLOB;
  }
}
