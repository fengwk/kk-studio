package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;
import java.util.UUID;

/**
 * 瞬时 USER_MESSAGE 附件内容：只存在于客户端命令请求（wire）与命令提交事务内，绝不允许进入 durable payload JSON。
 *
 * <p>{@code uploadId} 引用 {@code storage_upload} 的 READY 行；应用事务在同一外事务内消费该 upload 并把本内容替换为 {@link
 * ResourceMessageContent} 后才入队 durable command。durable codec 对 {@code attachment} discriminator 与
 * 本类型的 encode 一律拒绝。
 */
public record AttachmentMessageContent(UUID uploadId) implements AgentMessageContent {

  public AttachmentMessageContent {
    uploadId = Objects.requireNonNull(uploadId, "uploadId");
  }
}
