package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 产品 HTTP 命令 DTO；不包含 CUSTOM_MESSAGE 或 SYSTEM steering 字段。 */
@Data
public class HarnessCommandCreateDTO {

  /** SET_ENVIRONMENT、SET_AGENT、SET_MODEL 或 USER_MESSAGE。 */
  private String type;

  /** canonical UUID string 幂等键。 */
  private String idempotencyKey;

  /** USER_MESSAGE 的非空有序结构化内容。 */
  private List<HarnessUserMessageContentDTO> contents;

  /** SET_AGENT 的目标 Agent 名。 */
  private String agentName;

  /** SET_MODEL 的目标模型。 */
  private HarnessModelSelectionDTO model;

  /** SET_ENVIRONMENT 的 workspace path；显式 null 表示清除 workspace path。 */
  private String workspacePath;

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
  private boolean workspacePathFieldPresent;

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

  @JsonSetter("workspacePath")
  public void setWorkspacePath(String value) {
    this.workspacePath = value;
    this.workspacePathFieldPresent = true;
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
  public boolean hasWorkspacePathField() {
    return workspacePathFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown HTTP command field: " + name);
  }
}
