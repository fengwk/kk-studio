package fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** {@code cloud_text_revision} 数据库持久化对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudTextRevisionDO {

  private UUID nodeId;
  private Long revision;
  private String content;
  private Long sizeBytes;
  private String sha256;
  private Boolean isCurrent;
  private Instant createTime;
}
