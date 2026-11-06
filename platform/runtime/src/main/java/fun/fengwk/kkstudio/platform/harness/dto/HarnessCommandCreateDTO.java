package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.share.catalog.HarnessExecutionSelectionDTO;

import java.util.List;

/**
 * 产品 HTTP 命令 union：每条命令只携带自身语义需要的字段。
 *
 * <p>不包含 CUSTOM_MESSAGE 或 SYSTEM steering 形态；创建型批次与既有 Thread 批次共用本 union。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HarnessCommandCreateDTO.UserMessageDTO.class, name = "USER_MESSAGE"),
  @JsonSubTypes.Type(value = HarnessCommandCreateDTO.GoalDTO.class, name = "GOAL"),
  @JsonSubTypes.Type(value = HarnessCommandCreateDTO.SetAgentDTO.class, name = "SET_AGENT"),
  @JsonSubTypes.Type(value = HarnessCommandCreateDTO.SetExecutionDTO.class, name = "SET_EXECUTION"),
  @JsonSubTypes.Type(
      value = HarnessCommandCreateDTO.SetEnvironmentDTO.class,
      name = "SET_ENVIRONMENT")
})
public sealed interface HarnessCommandCreateDTO {

  /** canonical UUID string 幂等键。 */
  String idempotencyKey();

  /** 非空有序结构化用户消息。 */
  @JsonTypeName("USER_MESSAGE")
  record UserMessageDTO(String idempotencyKey, List<HarnessUserMessageContentDTO> contents)
      implements HarnessCommandCreateDTO {}

  /** 目标 Goal；{@code text = null} 表示清除该 branch 的 Goal。 */
  @JsonTypeName("GOAL")
  record GoalDTO(
      String idempotencyKey, @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String text)
      implements HarnessCommandCreateDTO {}

  /** 切换目标 Agent。 */
  @JsonTypeName("SET_AGENT")
  record SetAgentDTO(String idempotencyKey, String agentName) implements HarnessCommandCreateDTO {}

  /** 切换目标执行配置。 */
  @JsonTypeName("SET_EXECUTION")
  record SetExecutionDTO(String idempotencyKey, HarnessExecutionSelectionDTO execution)
      implements HarnessCommandCreateDTO {}

  /** 选择目标 Environment；{@code environmentName = null} 表示解除该 branch 的环境选择。 */
  @JsonTypeName("SET_ENVIRONMENT")
  record SetEnvironmentDTO(
      String idempotencyKey,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String environmentName)
      implements HarnessCommandCreateDTO {}
}
