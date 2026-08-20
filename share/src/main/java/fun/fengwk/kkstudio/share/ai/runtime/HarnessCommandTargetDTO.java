package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/** 产品命令批次的严格 target union wire DTO。 */
@Data
public class HarnessCommandTargetDTO {

  /** target 类型：NEW_SESSION、ENTRY 或 THREAD。 */
  private String type;

  private String sessionId;
  private String startEntryId;
  private String threadId;
  private HarnessBranchSettingsDTO rootSettings;
  private Boolean yoloEnabled;
  private String expectedHeadEntryId;
  private String expectedNextCommandSequence;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean sessionIdFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean startEntryIdFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean threadIdFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean rootSettingsFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean yoloEnabledFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean expectedHeadEntryIdFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean expectedNextCommandSequenceFieldPresent;

  @JsonSetter("type")
  public void setType(Object value) {
    this.type = HarnessRuntimeDtoSupport.requireJsonString(value, "target.type");
  }

  @JsonSetter("sessionId")
  public void setSessionId(Object value) {
    this.sessionId = HarnessRuntimeDtoSupport.requireJsonString(value, "target.sessionId");
    this.sessionIdFieldPresent = true;
  }

  @JsonSetter("startEntryId")
  public void setStartEntryId(Object value) {
    this.startEntryId = HarnessRuntimeDtoSupport.requireJsonString(value, "target.startEntryId");
    this.startEntryIdFieldPresent = true;
  }

  @JsonSetter("threadId")
  public void setThreadId(Object value) {
    this.threadId = HarnessRuntimeDtoSupport.requireJsonString(value, "target.threadId");
    this.threadIdFieldPresent = true;
  }

  @JsonSetter("rootSettings")
  public void setRootSettings(HarnessBranchSettingsDTO value) {
    this.rootSettings = value;
    this.rootSettingsFieldPresent = true;
  }

  @JsonSetter("yoloEnabled")
  public void setYoloEnabled(Object value) {
    this.yoloEnabled = HarnessRuntimeDtoSupport.requireJsonBoolean(value, "target.yoloEnabled");
    this.yoloEnabledFieldPresent = true;
  }

  @JsonSetter("expectedHeadEntryId")
  public void setExpectedHeadEntryId(Object value) {
    this.expectedHeadEntryId =
        HarnessRuntimeDtoSupport.requireJsonString(value, "target.expectedHeadEntryId");
    this.expectedHeadEntryIdFieldPresent = true;
  }

  @JsonSetter("expectedNextCommandSequence")
  public void setExpectedNextCommandSequence(Object value) {
    this.expectedNextCommandSequence =
        HarnessRuntimeDtoSupport.requireJsonString(value, "target.expectedNextCommandSequence");
    this.expectedNextCommandSequenceFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasSessionIdField() {
    return sessionIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasStartEntryIdField() {
    return startEntryIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasThreadIdField() {
    return threadIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasRootSettingsField() {
    return rootSettingsFieldPresent;
  }

  @JsonIgnore
  public boolean hasYoloEnabledField() {
    return yoloEnabledFieldPresent;
  }

  @JsonIgnore
  public boolean hasExpectedHeadEntryIdField() {
    return expectedHeadEntryIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasExpectedNextCommandSequenceField() {
    return expectedNextCommandSequenceFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown command target field: " + name);
  }
}
