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

  /** SET_ENVIRONMENT、SET_AGENT、SET_MODEL、SET_ACTIVE_TOOLS 或 USER_MESSAGE。 */
  private String type;

  /** canonical UUID string 幂等键。 */
  private String clientCommandId;

  /** USER_MESSAGE 的非空有序结构化内容。 */
  private List<HarnessUserMessageContentDTO> contents;

  /** SET_AGENT 的目标 Agent 名。 */
  private String agentName;

  /** SET_MODEL 的目标模型。 */
  private HarnessModelSelectionDTO model;

  /** SET_ACTIVE_TOOLS 的激活工具名称列表。 */
  private List<String> activeTools;

  /** SET_ENVIRONMENT 的完整 binding；显式 null 表示解绑。 */
  private EnvironmentBindingDTO environment;

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
  private boolean activeToolsFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean environmentFieldPresent;

  @JsonSetter("type")
  public void setType(Object value) {
    this.type = HarnessRuntimeDtoSupport.requireJsonString(value, "command.type");
  }

  @JsonSetter("clientCommandId")
  public void setClientCommandId(Object value) {
    this.clientCommandId =
        HarnessRuntimeDtoSupport.requireJsonString(value, "command.clientCommandId");
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

  @JsonSetter("activeTools")
  public void setActiveTools(List<String> value) {
    this.activeTools = value;
    this.activeToolsFieldPresent = true;
  }

  @JsonSetter("environment")
  public void setEnvironment(EnvironmentBindingDTO value) {
    this.environment = value;
    this.environmentFieldPresent = true;
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
  public boolean hasActiveToolsField() {
    return activeToolsFieldPresent;
  }

  @JsonIgnore
  public boolean hasEnvironmentField() {
    return environmentFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown HTTP command field: " + name);
  }
}
