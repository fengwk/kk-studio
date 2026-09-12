package fun.fengwk.kkstudio.platform.cloudfs.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Cloud File System 文本版本领域对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextRevision {

  /** 关联的 TEXT 节点 UUID。 */
  private UUID nodeId;

  /** 单调递增正整数版本号（从 1 开始）。 */
  private long revision;

  /** 权威 UTF-8 文本内容。 */
  private String content;

  /** 严格 UTF-8 字节大小。 */
  private long sizeBytes;

  /** 内容 SHA-256 小写十六进制摘要（64 字符）。 */
  private String sha256;

  /** 是否为该节点的当前活跃版本。 */
  private boolean current;

  /** 创建时间。 */
  private Instant createdAt;
}
