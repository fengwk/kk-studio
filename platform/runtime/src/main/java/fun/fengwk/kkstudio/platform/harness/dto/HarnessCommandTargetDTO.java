package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 创建型命令批次的 target union。
 *
 * <p>只表达 NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION 三种创建语义；既有 Thread 的继续写入使用 {@link
 * HarnessThreadCommandBatchDTO}。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HarnessCommandTargetDTO.NewSessionDTO.class, name = "NEW_SESSION"),
  @JsonSubTypes.Type(value = HarnessCommandTargetDTO.NewThreadDTO.class, name = "NEW_THREAD"),
  @JsonSubTypes.Type(
      value = HarnessCommandTargetDTO.NewForkedSessionDTO.class,
      name = "NEW_FORKED_SESSION")
})
public sealed interface HarnessCommandTargetDTO {

  /** 新建 Session 及其 root Thread。 */
  @JsonTypeName("NEW_SESSION")
  record NewSessionDTO(
      String sessionId, String threadId, HarnessBranchSettingsDTO rootSettings, boolean yoloEnabled)
      implements HarnessCommandTargetDTO {}

  /** 在既有 Session 的指定 Entry 上新建分支 Thread。 */
  @JsonTypeName("NEW_THREAD")
  record NewThreadDTO(
      String sessionId,
      String startEntryId,
      String threadId,
      String threadName,
      boolean yoloEnabled)
      implements HarnessCommandTargetDTO {}

  /** 从既有执行根 Thread 的指定 Entry fork 出新 Session。 */
  @JsonTypeName("NEW_FORKED_SESSION")
  record NewForkedSessionDTO(
      String sourceThreadId,
      String startEntryId,
      String sessionId,
      String threadId,
      boolean yoloEnabled)
      implements HarnessCommandTargetDTO {}
}
