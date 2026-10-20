package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/**
 * Thread 流式追帧请求：客户端声明自己已观察到的 Model Invocation attempt，服务端只让当前 active owner 立即提交 checkpoint。
 *
 * <p>字段形状是冻结的前端契约：{@code invocationId} 必须是 canonical UUID string，{@code attempt} 必须是 JSON 正整数。
 * 请求只表达"补齐到哪个 attempt"，不携带任何 Thread head 或快照事实，服务端按自己的 durable 状态重新校验。
 */
@Data
public class HarnessThreadStreamCatchupDTO {

  /** 必填 canonical UUID string：目标 Model Invocation。 */
  private String invocationId;

  /** 必填 JSON 正整数：客户端观察到的 attempt。 */
  private Integer attempt;

  @JsonSetter("invocationId")
  public void setInvocationId(Object value) {
    this.invocationId = HarnessRuntimeDtoSupport.requireJsonString(value, "invocationId");
  }

  @JsonSetter("attempt")
  public void setAttempt(Object value) {
    this.attempt = HarnessRuntimeDtoSupport.requireJsonInteger(value, "attempt");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown stream catchup field: " + name);
  }
}
