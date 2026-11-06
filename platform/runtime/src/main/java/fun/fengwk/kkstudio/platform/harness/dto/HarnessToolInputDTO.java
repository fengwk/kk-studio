package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.UUID;

/**
 * 一次人工问卷提交的请求体：回答或明确拒答二者之一。
 *
 * <p>目标 Thread 与 Tool invocation 都在路径中；{@code submissionId} 是客户端生成的稳定幂等键，用于相同提交的精确重试。
 * 操作者身份只来自服务端认证上下文，请求体永远不能指定 actor。题目未作答以 {@code null} 答案表示，由 Runtime 按冻结问卷判定，不在边界上抹平。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HarnessToolInputDTO.Answer.class, name = "ANSWER"),
  @JsonSubTypes.Type(value = HarnessToolInputDTO.Decline.class, name = "DECLINE")
})
public sealed interface HarnessToolInputDTO
    permits HarnessToolInputDTO.Answer, HarnessToolInputDTO.Decline {

  /** 客户端生成的稳定幂等键。 */
  UUID submissionId();

  /** 按问题位置作答；内层 null 表示该题未填写。 */
  record Answer(UUID submissionId, List<List<String>> answers) implements HarnessToolInputDTO {}

  /** 明确拒答；不携带任何答案。 */
  record Decline(UUID submissionId) implements HarnessToolInputDTO {}
}
