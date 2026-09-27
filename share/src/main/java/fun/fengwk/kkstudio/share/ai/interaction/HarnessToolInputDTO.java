package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessRequestFormatException;

import java.util.List;

/**
 * 一次人工输入（{@code ask_user} 问卷）提交请求体。
 *
 * <p>统一交互入口只按 {@code interactionId}（路径）与 {@code threadId}（请求体）定位目标，因此客户端不需要预先知道该 Thread 属于 Chat 还是
 * Issue+Agent：服务端先解析产品 owner、按外层产品锁序串行化，再交给 Harness Runtime 提交。操作者身份不从 请求体取得：由服务端认证上下文（部署边界设置的
 * principal）确定，客户端无法伪造。{@code submissionId} 是客户端生成的稳定幂等键， 用于相同提交的精确重试；{@code declined}
 * 明确表达拒答，拒答时不得携带答案。
 */
@Data
public class HarnessToolInputDTO {

  private String threadId;

  private String submissionId;

  private Boolean declined;

  private List<List<String>> answers;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown tool input field: " + name);
  }
}
