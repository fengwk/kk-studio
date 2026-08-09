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
 */
@Data
public class HarnessThreadCommandCreateDTO {
  /**
   * 必填命令类型 discriminator，取 {@code ThreadCommandType} 枚举名：USER_MESSAGE / CUSTOM_MESSAGE / SET_AGENT
   * / SET_MODEL / SET_ACTIVE_TOOLS / SET_YOLO / SET_ENVIRONMENT；mapper 按类型严格校验 required/forbidden
   * 字段。
   */
  private String type;

  /** 必填稳定客户端幂等键（canonical name）：同一批命令重放返回既有行。 */
  private String clientCommandId;

  /**
   * 兼容消息正文：CUSTOM_MESSAGE 必填；旧 USER_MESSAGE 调用继续接受。新 USER_MESSAGE 推荐使用 {@link #text} 或 {@link
   * #contents}。
   */
  private String content;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean contentFieldPresent;

  /** USER_MESSAGE 的纯文本 shorthand，与 {@link #content} / {@link #contents} 互斥。 */
  private String text;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean textFieldPresent;

  /** USER_MESSAGE 的非空结构化内容，与 {@link #text} / {@link #content} 互斥。 */
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

  @JsonSetter("text")
  public void setText(String text) {
    this.text = text;
    this.textFieldPresent = true;
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
  public boolean hasTextField() {
    return textFieldPresent;
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
