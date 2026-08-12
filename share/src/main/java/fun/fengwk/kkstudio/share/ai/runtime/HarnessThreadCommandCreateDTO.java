package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 类型化 Thread mailbox 命令请求。
 *
 * <p>{@code type} 是 discriminator（USER_MESSAGE / CUSTOM_MESSAGE / SET_AGENT / SET_MODEL /
 * SET_ACTIVE_TOOLS / SET_YOLO / SET_ENVIRONMENT）；mapper 按 discriminator 严格校验 required/forbidden
 * 可选字段。{@code clientCommandId} 是稳定幂等键。
 *
 * <p>USER_MESSAGE 只接受一个非空、有序的 {@link #contents}（内容为 TEXT / ATTACHMENT）；不提供任何文本 shorthand。
 * CUSTOM_MESSAGE 使用 {@link #content} 单文本正文。
 */
@Data
public class HarnessThreadCommandCreateDTO {
  /**
   * 必填命令类型 discriminator，取 {@code ThreadCommandType} 枚举名：USER_MESSAGE / CUSTOM_MESSAGE / SET_AGENT
   * / SET_MODEL / SET_ACTIVE_TOOLS / SET_YOLO / SET_ENVIRONMENT；mapper 按类型严格校验 required/forbidden
   * 字段。
   */
  private String type;

  /** 必填稳定客户端幂等键（canonical UUID string）：同一批命令重放返回既有行。 */
  private String clientCommandId;

  /** CUSTOM_MESSAGE 必填的单文本正文；其余类型禁止提供（USER_MESSAGE 只接受 {@link #contents}）。 */
  private String content;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean contentFieldPresent;

  /** USER_MESSAGE 必填的非空有序结构化内容（TEXT / ATTACHMENT）；其余类型禁止提供。 */
  private List<HarnessUserMessageContentDTO> contents;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean contentsFieldPresent;

  /** 消息角色：仅 CUSTOM_MESSAGE 必填，取值仅限 SYSTEM 或 USER；其余类型禁止提供。 */
  private String role;

  /** 目标 Agent 名：仅 SET_AGENT 必填（canonical text）；其余类型禁止提供。 */
  private String agentName;

  /** 目标模型选择：仅 SET_MODEL 必填；其余类型禁止提供。 */
  private HarnessModelSelectionDTO model;

  /** 目标激活工具列表：仅 SET_ACTIVE_TOOLS 必填；其余类型禁止提供。 */
  private List<String> activeTools;

  /** 目标 YOLO 策略：仅 SET_YOLO 必填；其余类型禁止提供。 */
  private Boolean yoloEnabled;

  /** 目标 Environment 路由：仅 SET_ENVIRONMENT 必填；可空 canonical bounded 小写名称（null 表示解绑）；其余类型禁止提供。 */
  private String environmentName;

  @JsonSetter("content")
  public void setContent(String content) {
    this.content = content;
    this.contentFieldPresent = true;
  }

  @JsonSetter("contents")
  public void setContents(List<HarnessUserMessageContentDTO> contents) {
    this.contents = contents;
    this.contentsFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasContentField() {
    return contentFieldPresent;
  }

  @JsonIgnore
  public boolean hasContentsField() {
    return contentsFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Thread command field: " + name);
  }
}
