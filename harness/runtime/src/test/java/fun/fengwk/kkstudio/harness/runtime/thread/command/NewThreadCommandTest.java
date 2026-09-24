package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.util.List;
import java.util.UUID;

/**
 * NewThreadCommand 的 requestHash 语义：两参构造对 raw payload 计算；三参构造只校验 hash 格式（不重算）；{@code withPayload} 在
 * payload 被物化后保留原始 idempotencyKey / requestHash。
 */
class NewThreadCommandTest {

  @Test
  void twoArgConstructorComputesRawRequestHashFromPayload() {
    UserMessageCommandPayload raw = userPayloadWithAttachment();
    NewThreadCommand command = new NewThreadCommand(raw, TestIds.id(1));
    assertEquals(ThreadCommandPayloadJsonCodec.requestHash(raw), command.requestHash());
  }

  /** preflight 把 ATTACHMENT 物化为 RESOURCE：幂等键保留，durable payload 自身 hash 与 raw 不同。 */
  @Test
  void withPayloadPreservesRawRequestHashWhilePayloadChanges() {
    UserMessageCommandPayload rawPayload = userPayloadWithAttachment();
    NewThreadCommand raw = new NewThreadCommand(rawPayload, TestIds.id(1));
    UserMessageCommandPayload durablePayload = userPayloadWithResource();
    NewThreadCommand durable = raw.withPayload(durablePayload);

    // idempotencyKey 与 requestHash 保持原始 raw 值。
    assertEquals(raw.idempotencyKey(), durable.idempotencyKey());
    assertEquals(raw.requestHash(), durable.requestHash());
    // payload 已替换为 durable 形态。
    assertEquals(durablePayload, durable.payload());
    // durable payload 自身的 canonical request hash 与 raw hash 不同（内容变了，幂等键不变）。
    assertNotEquals(ThreadCommandPayloadJsonCodec.requestHash(durablePayload), raw.requestHash());
    // 两参构造同样以 raw 形态计算 hash，与 withPayload 结果一致。
    assertEquals(raw.requestHash(), new NewThreadCommand(rawPayload, TestIds.id(1)).requestHash());
  }

  /** 意图：图片输入档位是请求事实，同一 upload 的不同档位必须产生不同的原始请求 hash（幂等键不得吞掉档位差异）。 */
  @Test
  void rawRequestHashIsSensitiveToImageInputTier() {
    UUID uploadId = TestIds.id(77);
    UserMessageCommandPayload p720 = userPayloadWithAttachmentTier(uploadId, ImageInputTier.P720);
    UserMessageCommandPayload p1080 = userPayloadWithAttachmentTier(uploadId, ImageInputTier.P1080);
    UserMessageCommandPayload defaultTier = userPayloadWithAttachment(uploadId);

    String hashP720 = ThreadCommandPayloadJsonCodec.requestHash(p720);
    assertNotEquals(hashP720, ThreadCommandPayloadJsonCodec.requestHash(p1080));
    assertNotEquals(hashP720, ThreadCommandPayloadJsonCodec.requestHash(defaultTier));
    // 同一档位必须稳定：重试不会因为重新计算 hash 而被当成新请求。
    assertEquals(hashP720, ThreadCommandPayloadJsonCodec.requestHash(p720));
    assertEquals(
        hashP720,
        new NewThreadCommand(
                userPayloadWithAttachmentTier(uploadId, ImageInputTier.P720), TestIds.id(1))
            .requestHash());
  }

  /** 三参构造只严格校验 hash 格式，不重算也不要求等于 payload 自身 hash（因此可承载已物化命令）。 */
  @Test
  void canonicalConstructorValidatesOnlyHashFormat() {
    UserMessageCommandPayload durablePayload = userPayloadWithResource();
    // durable payload 携带一个与其自身 hash 不同的原始 raw hash：合法（幂等键与 payload 解耦）。
    String rawHash = ThreadCommandPayloadJsonCodec.requestHash(userPayloadWithAttachment());
    NewThreadCommand materialized = new NewThreadCommand(durablePayload, TestIds.id(1), rawHash);
    assertEquals(rawHash, materialized.requestHash());
    // 非 64 小写 hex / null 一律拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> new NewThreadCommand(durablePayload, TestIds.id(1), "not-a-hash"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NewThreadCommand(durablePayload, TestIds.id(1), null));
  }

  private static UserMessageCommandPayload userPayloadWithAttachment() {
    return userPayloadWithAttachment(TestIds.id(77));
  }

  private static UserMessageCommandPayload userPayloadWithAttachment(UUID uploadId) {
    return new UserMessageCommandPayload(
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new AttachmentMessageContent(uploadId),
                new TextMessageContent("please summarize"))));
  }

  private static UserMessageCommandPayload userPayloadWithAttachmentTier(
      UUID uploadId, ImageInputTier tier) {
    return new UserMessageCommandPayload(
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new AttachmentMessageContent(uploadId, tier),
                new TextMessageContent("please summarize"))));
  }

  private static UserMessageCommandPayload userPayloadWithResource() {
    return new UserMessageCommandPayload(
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                ResourceMessageContent.media(TestIds.id(88), "photo.png"),
                new TextMessageContent("please summarize"))));
  }
}
