package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Store 分配 durable sequence 之前的不可变 command request。
 *
 * <p>{@code requestHash} 是<b>原始客户端请求</b>的 canonical SHA-256（{@link
 * ThreadCommandPayloadJsonCodec#requestHash}， 作用于含 ordered contents 与 uploadId 的 raw 请求形态），由服务端对
 * raw payload 计算：两参构造自动计算；三参构造只严格校验 hash 格式（64 位小写 hex），<b>不重算也不要求等于当前 payload 的 hash</b>——因为
 * preflight 可能已把瞬时 ATTACHMENT 物化为 durable RESOURCE，durable payload 自身的 canonical hash 必然与原始请求 hash
 * 不同。
 *
 * <p>因此三参构造可用于承载「payload 已物化、但幂等键（{@code clientCommandId} + {@code requestHash}）保持原始值」的命令；
 * preflight 应使用 {@link #withPayload} 替换 durable payload，以确保同 clientCommandId 的 raw 重试仍能命中 ordered
 * replay。下游 platform 的 attachment 物化逻辑应使用该方法。
 */
public record NewThreadCommand(
    ThreadCommandPayload payload, UUID clientCommandId, String requestHash) {

  /** 由服务端按 raw payload 计算 request hash（两参便捷构造，仅用于 raw 请求形态的命令）。 */
  public NewThreadCommand(ThreadCommandPayload payload, UUID clientCommandId) {
    this(
        payload,
        clientCommandId,
        ThreadCommandPayloadJsonCodec.requestHash(Objects.requireNonNull(payload, "payload")));
  }

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestHash must be 64 lowercase hexadecimal characters");
    }
  }

  /**
   * 用 durable payload 重建命令，保留原始 {@code clientCommandId} 与 {@code requestHash}。
   *
   * <p>preflight 或应用 use-case 把瞬时 ATTACHMENT 物化为 durable RESOURCE 后调用：结果命令的 payload 是 durable
   * 形态，但幂等键保持客户端原始值，因此携带原始 ATTACHMENT 的请求重试仍能按 clientCommandId + requestHash 命中 ordered replay。
   */
  public NewThreadCommand withPayload(ThreadCommandPayload durablePayload) {
    Objects.requireNonNull(durablePayload, "durablePayload");
    return new NewThreadCommand(durablePayload, clientCommandId, requestHash);
  }
}
