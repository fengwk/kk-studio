package fun.fengwk.kkstudio.platform.orchestration;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * USER_MESSAGE 内容的共享规范化：正式发送 preflight 与只读请求预览复用同一份「ATTACHMENT 换算 + Session 归属校验 + imageTier
 * 规范化」，只有 READY upload 的取得方式不同（发送侧消费并 retain，预览侧只读 peek）。
 *
 * <p>瞬时 ATTACHMENT 一律被换算为携带权威文件名与权威 blobId 的 RESOURCE 内容（事实只来自上传行，绝不信任客户端内容），既有 RESOURCE 必须已被当前
 * Session 持有。换算结果只是内存中的命令内容：发送侧随后把它持久化为 durable 命令，预览侧只存在于本次 candidate 中，两者都不改变 idempotencyKey /
 * requestHash，因此同 idempotencyKey 的 raw 重试仍能命中 ordered replay。
 */
public final class UserMessageContentPreparer {

  /** READY 上传事实的取得策略：发送侧消费（锁行 + retain + 标记 cleanup），预览侧只读 peek。 */
  @FunctionalInterface
  public interface ReadyAttachmentSource {

    /**
     * 取得该 ATTACHMENT 对应的权威 blobId 与文件名；不满足 READY 时由实现抛出各自的确定性拒绝。
     *
     * @param sessionId 目标 Session（发送侧据此 retain ref）
     * @param uploadId 客户端声明的上传 id
     */
    ReadyAttachment acquire(UUID sessionId, UUID uploadId);
  }

  /** 一条 ATTACHMENT 的不可变权威事实。 */
  public record ReadyAttachment(UUID blobId, String filename) {

    public ReadyAttachment {
      Objects.requireNonNull(blobId, "blobId");
      Objects.requireNonNull(filename, "filename");
    }
  }

  private final SessionBlobRefManager refManager;
  private final StorageBlobManager blobManager;
  private final ReadyAttachmentSource attachmentSource;

  public UserMessageContentPreparer(
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager,
      ReadyAttachmentSource attachmentSource) {
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.attachmentSource = Objects.requireNonNull(attachmentSource, "attachmentSource");
  }

  /** 转换整个 batch 的 USER_MESSAGE 内容；非 USER_MESSAGE 命令原样保留。 */
  public List<NewThreadCommand> prepare(UUID sessionId, List<NewThreadCommand> commands) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(commands, "commands");
    List<NewThreadCommand> prepared = new ArrayList<>(commands.size());
    for (NewThreadCommand command : commands) {
      if (!(command.payload() instanceof UserMessageCommandPayload user)) {
        prepared.add(command);
        continue;
      }
      AgentMessage message = user.message();
      List<AgentMessageContent> contents = new ArrayList<>(message.contents().size());
      for (AgentMessageContent content : message.contents()) {
        if (content instanceof AttachmentMessageContent attachment) {
          contents.add(attachmentToResource(sessionId, attachment));
        } else if (content instanceof ResourceMessageContent resource) {
          contents.add(requireOwnedResource(sessionId, resource));
        } else {
          contents.add(content);
        }
      }
      prepared.add(
          command.withPayload(
              new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, contents))));
    }
    return List.copyOf(prepared);
  }

  /** 瞬时 ATTACHMENT 换算为 durable RESOURCE：blobId/filename 只来自权威上传事实。 */
  private ResourceMessageContent attachmentToResource(
      UUID sessionId, AttachmentMessageContent attachment) {
    ReadyAttachment ready = attachmentSource.acquire(sessionId, attachment.uploadId());
    return ResourceMessageContent.media(
        ready.blobId(),
        ready.filename(),
        null,
        imageTierForBlob(ready.blobId(), attachment.imageTier()));
  }

  /** RESOURCE 只复用当前 Session 已持有的 durable ref，不新增 retain，也不信任跨 Session blob id。 */
  private ResourceMessageContent requireOwnedResource(
      UUID sessionId, ResourceMessageContent resource) {
    if (!refManager.contains(sessionId, resource.blobId())) {
      throw new IllegalArgumentException("Resource is not owned by the current session");
    }
    if (resource.imageTier() == null) {
      return resource;
    }
    ImageInputTier imageTier = imageTierForBlob(resource.blobId(), resource.imageTier());
    if (imageTier == resource.imageTier()) {
      return resource;
    }
    return ResourceMessageContent.media(
        resource.blobId(), resource.name(), resource.preview(), imageTier);
  }

  /**
   * 只有图片媒体真正使用档位：wire 上的档位对 ATTACHMENT/RESOURCE 缺省即 720P，非图片媒体在这里收敛为 null，绝不持久化 无意义的档位（音频、视频、PDF
   * 与外部化文本都不携带档位）。
   */
  private ImageInputTier imageTierForBlob(UUID blobId, ImageInputTier requested) {
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null
        || blob.getState() != StorageBlobState.ACTIVE
        || blob.getMediaType() == null
        || !blob.getMediaType().startsWith("image/")) {
      return null;
    }
    return requested == null ? ImageInputTier.P720 : requested;
  }
}
