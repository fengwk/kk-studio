package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Store 分配 durable sequence 之前的不可变 command request。
 *
 * <p>{@code requestHash} 由服务端 canonical payload codec 在构造时计算（{@link
 * ThreadCommandPayloadJsonCodec#requestHash}），调用方不传入也不能覆盖：服务端永远是 request hash 的 truth，任何 传入 hash
 * 与实际不匹配都在构造时被拒绝。
 */
public record NewThreadCommand(
    ThreadCommandPayload payload, UUID clientCommandId, String requestHash) {

  public NewThreadCommand(ThreadCommandPayload payload, UUID clientCommandId) {
    this(
        payload,
        clientCommandId,
        ThreadCommandPayloadJsonCodec.requestHash(Objects.requireNonNull(payload, "payload")));
  }

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    String canonical = ThreadCommandPayloadJsonCodec.requestHash(payload);
    if (!canonical.equals(requestHash)) {
      throw new IllegalArgumentException(
          "requestHash must be the canonical payload hash computed by the server codec");
    }
  }
}
