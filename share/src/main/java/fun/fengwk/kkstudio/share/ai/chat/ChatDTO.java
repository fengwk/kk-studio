package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/** Chat 集合的公开读表示。 */
@Data
public class ChatDTO {

  /** Chat 业务主键：canonical UUID string（PostgreSQL uuid，由应用生成）。 */
  private String id;

  /** 聊天标题：非空白且 ≤256 字符。 */
  private String title;

  /** 必填可见 Agent 身份；Agent 删除后该值可能过期。 */
  private String agentName;

  /** 可空的默认分支 workspace path。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String workspacePath;

  /** 可见的发送权限模式。 */
  private boolean yoloEnabled;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant，单调不减）。 */
  private Instant updateTime;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown chat field: " + name);
  }
}
