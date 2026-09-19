package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stop 取消内容中按 sequence 还原的一条 user-like 消息。
 *
 * <p>仅 USER_MESSAGE 与用户 CUSTOM_MESSAGE 会被还原；SET_* 与运行时提醒不出现。{@code contents} 为消息的全部内容 （不含
 * role——由命令类型隐含），按取消时 sequence 升序返回。
 */
public record CancelledUserMessage(
    long sequence, UUID idempotencyKey, List<AgentMessageContent> contents) {

  public CancelledUserMessage {
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
  }
}
