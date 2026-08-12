package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Store 分配 durable sequence 之前的不可变 command request。
 *
 * <p>{@code requestHash} 是客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256，由调用方在 映射
 * wire 输入时计算（{@link ThreadCommandPayloadJsonCodec#requestHash}）；它独立于 durable payload 表示，
 * 使附件物化后的重放仍能命中同一 hash。
 */
public record NewThreadCommand(
    ThreadCommandPayload payload, UUID clientCommandId, String requestHash) {

  private static final Pattern REQUEST_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    if (requestHash == null || !REQUEST_HASH_PATTERN.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be 64 lowercase hexadecimal characters");
    }
  }
}
