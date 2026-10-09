package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建型命令批次的严格 target union wire DTO。
 *
 * <p>只表达 NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION 三种创建语义；既有 Thread 的继续写入使用 {@link
 * HarnessThreadCommandBatchDTO}，不再通过本 union 以 THREAD target 承载。
 */
@Data
public class HarnessCommandTargetDTO {

  /** target 类型：NEW_SESSION、NEW_THREAD 或 NEW_FORKED_SESSION。 */
  private String type;

  private String sessionId;
  private String startEntryId;
  private String threadId;

  /** NEW_THREAD 的分支显示名；必须显式给出且非 blank（NEW_SESSION / NEW_FORKED_SESSION 禁用）。 */
  private String threadName;

  /** NEW_FORKED_SESSION 的来源执行根 Thread；必须显式给出（其它类型禁用）。 */
  private String sourceThreadId;

  private HarnessBranchSettingsDTO rootSettings;
  private Boolean yoloEnabled;

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
  private boolean threadNameFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean sourceThreadIdFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean rootSettingsFieldPresent;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean yoloEnabledFieldPresent;

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

  @JsonSetter("threadName")
  public void setThreadName(Object value) {
    this.threadName = HarnessRuntimeDtoSupport.requireJsonString(value, "target.threadName");
    this.threadNameFieldPresent = true;
  }

  @JsonSetter("sourceThreadId")
  public void setSourceThreadId(Object value) {
    this.sourceThreadId =
        HarnessRuntimeDtoSupport.requireJsonString(value, "target.sourceThreadId");
    this.sourceThreadIdFieldPresent = true;
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
  public boolean hasThreadNameField() {
    return threadNameFieldPresent;
  }

  @JsonIgnore
  public boolean hasSourceThreadIdField() {
    return sourceThreadIdFieldPresent;
  }

  @JsonIgnore
  public boolean hasRootSettingsField() {
    return rootSettingsFieldPresent;
  }

  @JsonIgnore
  public boolean hasYoloEnabledField() {
    return yoloEnabledFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command target field: " + name);
  }
}
