package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;

import java.util.Objects;
import java.util.UUID;

/**
 * 瞬时 USER_MESSAGE 附件内容：只存在于客户端命令请求（wire）与命令提交事务内，绝不允许进入 durable payload JSON。
 *
 * <p>{@code uploadId} 引用 {@code storage_upload} 的 READY 行；应用事务在同一外事务内消费该 upload 并把本内容替换为 {@link
 * ResourceMessageContent} 后才入队 durable command。durable codec 对 {@code attachment} discriminator 与
 * 本类型的 encode 一律拒绝。
 *
 * <p>{@code imageTier} 是客户端在 wire 上冻结的图片输入档位；缺省表示使用平台默认（720P），非图片 upload 在消费事务内收敛为
 * null。该值参与请求形态编码，因此同一幂等键下的不同档位是不同的请求。
 */
public record AttachmentMessageContent(UUID uploadId, ImageInputTier imageTier)
    implements AgentMessageContent {

  public AttachmentMessageContent {
    uploadId = Objects.requireNonNull(uploadId, "uploadId");
  }

  /** 未显式选择档位的附件：图片按平台默认（720P）物化。 */
  public AttachmentMessageContent(UUID uploadId) {
    this(uploadId, null);
  }
}
