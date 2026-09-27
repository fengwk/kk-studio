package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/**
 * 产品命令批次的 owner wire DTO。
 *
 * <p>只表达真正持有 Harness Session 的产品归属：{@code CHAT} 由 {@code chat_session} 直接持有，给出 {@code chatId}；
 * {@code ISSUE_AGENT} 由 {@code (issueId, agentName) -> threadId} 稳定绑定持有，给出 {@code issueId} 与 {@code
 * agentName}。 Canvas 不持有 Harness Session，因此没有 owner 形态；Issue+Agent 命令的公共入口是 Issue 业务工作流，本 DTO 只描述
 * owner 事实本身。
 */
@Data
public class HarnessCommandOwnerDTO {

  /** owner 类型：{@code CHAT} 或 {@code ISSUE_AGENT}。 */
  private String type;

  /** Chat owner 的 canonical UUID string。 */
  private String chatId;

  /** Issue+Agent owner 的 canonical UUID string。 */
  private String issueId;

  /** Issue+Agent owner 的 Agent 自然名称。 */
  private String agentName;

  @JsonSetter("type")
  public void setType(Object value) {
    this.type = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.type");
  }

  @JsonSetter("chatId")
  public void setChatId(Object value) {
    this.chatId = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.chatId");
  }

  @JsonSetter("issueId")
  public void setIssueId(Object value) {
    this.issueId = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.issueId");
  }

  @JsonSetter("agentName")
  public void setAgentName(Object value) {
    this.agentName = HarnessRuntimeDtoSupport.requireJsonString(value, "owner.agentName");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command owner field: " + name);
  }
}
