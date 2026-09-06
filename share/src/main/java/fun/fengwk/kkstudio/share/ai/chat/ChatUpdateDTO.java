package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/**
 * {@code /api/ai/chats/{chatId}} 的更新请求体。
 *
 * <p>部分更新：省略的字段保留当前值。提供 {@code title} 时必填；提供的 {@code agentName} 必须是非空白且已存在的 Agent definition
 * 名。{@link #expectedVersion} 每次更新必填。
 */
@Data
public class ChatUpdateDTO {

  /** 部分更新：省略（null）保留当前值；提供时必须为非空白（trim 后）且 ≤256 字符。 */
  private String title;

  /** 部分更新：省略（null）保留当前值；提供时必须为已存在的 Agent definition 名（约束同创建）。 */
  private String agentName;

  /** 部分更新：仅在 {@link #workspacePathProvided} 为 true 时生效；显式 null 表示清除默认 workspace path。 */
  private String workspacePath;

  @JsonIgnore private boolean workspacePathProvided;

  @JsonSetter("workspacePath")
  public void setWorkspacePath(String workspacePath) {
    this.workspacePath = workspacePath;
    this.workspacePathProvided = true;
  }

  @JsonIgnore
  public boolean isWorkspacePathProvided() {
    return workspacePathProvided;
  }

  /** 部分更新：仅在 {@link #yoloEnabledProvided} 为 true 时生效，且显式提供时不得为 null。 */
  private Boolean yoloEnabled;

  @JsonIgnore private boolean yoloEnabledProvided;

  @JsonSetter("yoloEnabled")
  public void setYoloEnabled(Boolean yoloEnabled) {
    this.yoloEnabled = yoloEnabled;
    this.yoloEnabledProvided = true;
  }

  @JsonIgnore
  public boolean isYoloEnabledProvided() {
    return yoloEnabledProvided;
  }

  /** 乐观并发控制：必须与服务器当前 chat.version 精确匹配。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown chat update field: " + name);
  }
}
