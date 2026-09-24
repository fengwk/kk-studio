package fun.fengwk.kkstudio.share.project;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Issue + Agent 稳定归属公开传输对象：唯一 {@code (issueId, agentName)} 到 Session 与工作 Branch 的绑定。
 *
 * <p>归属随 Issue 稳定存在，权限随当前 Run 改变；{@code role} 由 Issue 当前职责配置推导（EXECUTOR/REVIEWER）。 {@code branchId}
 * 即该 Agent 在该 Issue 上的工作 Branch（内部 Harness Thread）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueAgentSessionDTO {

  private String id;
  private String issueId;
  private String agentName;
  private String role;
  private String sessionId;
  private String branchId;
  private String createdAt;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("Unknown response field");
  }
}
