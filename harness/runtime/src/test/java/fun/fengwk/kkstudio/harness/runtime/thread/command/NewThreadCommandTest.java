package fun.fengwk.kkstudio.harness.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.util.List;

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
    return new UserMessageCommandPayload(
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                new AttachmentMessageContent(TestIds.id(77)),
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
