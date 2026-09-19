package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 产品 HTTP 命令 DTO；不包含 CUSTOM_MESSAGE 或 SYSTEM steering 字段。 */
@Data
public class HarnessCommandCreateDTO {

  /** USER_MESSAGE、SET_AGENT、SET_MODEL 或 SET_ENVIRONMENT。 */
  private String type;

  /** canonical UUID string 幂等键。 */
  private String idempotencyKey;

  /** USER_MESSAGE 的非空有序结构化内容。 */
  private List<HarnessUserMessageContentDTO> contents;

  /** SET_AGENT 的目标 Agent 名。 */
  private String agentName;

  /** SET_MODEL 的目标模型。 */
  private HarnessModelSelectionDTO model;

  /**
   * SET_ENVIRONMENT 的目标 Environment 名；null 表示解除该 branch 的环境选择。
   *
   * <p>该字段是否出现由 {@link #hasEnvironmentNameField()} 单独跟踪：SET_ENVIRONMENT 必须显式携带它（即使为 null）， 其余命令
   * 携带即拒绝。HTTP 全局配置默认省略 null，因此这里强制 {@link JsonInclude.Include#ALWAYS} 以便请求形态可精确重放。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean contentsFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean agentNameFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean modelFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean environmentNameFieldPresent;

  @JsonSetter("type")
  public void setType(Object value) {
    this.type = HarnessRuntimeDtoSupport.requireJsonString(value, "command.type");
  }

  @JsonSetter("idempotencyKey")
  public void setIdempotencyKey(Object value) {
    this.idempotencyKey =
        HarnessRuntimeDtoSupport.requireJsonString(value, "command.idempotencyKey");
  }

  @JsonSetter("contents")
  public void setContents(List<HarnessUserMessageContentDTO> value) {
    this.contents = value;
    this.contentsFieldPresent = true;
  }

  @JsonSetter("agentName")
  public void setAgentName(Object value) {
    this.agentName = HarnessRuntimeDtoSupport.requireJsonString(value, "command.agentName");
    this.agentNameFieldPresent = true;
  }

  @JsonSetter("model")
  public void setModel(HarnessModelSelectionDTO value) {
    this.model = value;
    this.modelFieldPresent = true;
  }

  @JsonSetter("environmentName")
  public void setEnvironmentName(Object value) {
    this.environmentName =
        HarnessRuntimeDtoSupport.requireJsonString(value, "command.environmentName");
    this.environmentNameFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasContentsField() {
    return contentsFieldPresent;
  }

  @JsonIgnore
  public boolean hasAgentNameField() {
    return agentNameFieldPresent;
  }

  @JsonIgnore
  public boolean hasModelField() {
    return modelFieldPresent;
  }

  @JsonIgnore
  public boolean hasEnvironmentNameField() {
    return environmentNameFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown HTTP command field: " + name);
  }
}
