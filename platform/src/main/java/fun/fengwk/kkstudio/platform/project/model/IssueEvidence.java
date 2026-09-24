package fun.fengwk.kkstudio.platform.project.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue 公开证据：Issue 自有 owner edge 持有的一个已发布 Blob 引用。
 *
 * <p>身份是 {@code (issueId, blobId)}：同一 Issue 对同一 Blob 至多一条，因此重复发布天然幂等。{@code name} 只在人工上传时
 * 具有权威值（取自上传行）；执行者 final 只携带规范 URI，不携带可伪造的文件名，因此为空。对外 URI 由 blob id 派生 （{@code
 * kkstudio:/resources/<blobId>}），绝不持久化。
 */
@Data
@Builder
public class IssueEvidence {

  private UUID issueId;
  private UUID blobId;
  private IssueEvidenceOrigin origin;
  private UUID runId;
  private String name;
  private Instant createdAt;
}
