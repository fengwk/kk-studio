package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code project_issue_activity} 行：有序、幂等的 Issue 事实流。
 *
 * <p>RUN 只引用 Run 且无 body/data 副本；COMMENT/INSTRUCTION 使用正文；SPEC_CHANGE/STATE_CHANGE/CONTROL 使用 typed
 * data。 同一 Issue 的 {@code idempotencyKey} 唯一，请求指纹为请求正文的 SHA-256。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueActivity {

  private UUID issueId;
  private long sequence;
  private IssueActivityKind kind;
  private IssueActivityActorType actorType;
  private String actorAgentName;
  private UUID runId;
  private String body;

  /** typed data JSON object 文本，非事件类为空对象 {@code {}}。 */
  private String data;

  private String idempotencyKey;
  private String requestHash;
  private Instant createdAt;
}
