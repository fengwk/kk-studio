package fun.fengwk.kkstudio.platform.cloudfs.repository.postgresql.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** {@code cloud_node} 数据库持久化对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudNodeDO {

  private UUID id;
  private UUID parentId;
  private String name;
  private String kind;
  private Long version;
  private UUID blobId;
  private Instant createTime;
  private Instant updateTime;
}
