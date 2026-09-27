package fun.fengwk.kkstudio.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code project_issue_work} 行：每个 Issue 至多一行的 durable 调度邮箱。
 *
 * <p>只表达「重新检查当前 Issue」的唤醒与 lease 围栏，不携带已过时的执行决定；{@code wakeVersion} 防止旧 Worker 完成 claim 时吞掉新唤醒。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueWork {

  private UUID issueId;
  private long wakeVersion;
  private Instant dueAt;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant createdAt;
  private Instant updatedAt;
}
