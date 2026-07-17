package fun.fengwk.kkstudio.harness.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.List;

/** {@link ControlMessageCodec} round-trip 与对非 USER / 损坏 payload 的拒绝。 */
public class ControlMessageCodecTest {

  private final ControlMessageCodec codec = new ControlMessageCodec();

  @Test
  public void roundTripsPlainUserText() {
    AgentMessage message =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi")));
    AgentMessage decoded = codec.decode(codec.encode(message));
    assertEquals(message, decoded);
  }

  @Test
  public void rejectsAssistantOnEncode() {
    AgentMessage message =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi")));
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    assertTrue(exception.getMessage().contains("USER"));
  }

  @Test
  public void rejectsSystemOnEncode() {
    AgentMessage message =
        new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("sys")));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
  }

  @Test
  public void rejectsAssistantOnDecode() {
    String json =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}],"
            + "\"assistantMetadata\":{\"stopReason\":\"COMPLETED\",\"usage\":{"
            + "\"inputTokens\":0,\"outputTokens\":0,\"cacheReadTokens\":0,"
            + "\"cacheWriteTokens\":0,\"reasoningTokens\":0},"
            + "\"cost\":{\"currency\":\"USD\",\"amount\":\"0\"}}}}";
    // 关键保证：ASSISTANT 在我们这条链路上永远不能被解码成可用的 AgentMessage；
    // 既可能在底层 codec 因结构性约束抛错，也可能在我们的 requireUser 抛错，
    // 但都必须明确抛 IllegalArgumentException 而不能沉默接受。
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }

  /** decode 时遇到 role=SYSTEM 也必须拒绝：USER 是控制队列的唯一合法角色。 */
  @Test
  public void rejectsSystemOnDecode() {
    // 即使 metadata/role 任意变化，SYSTEM 在我们这条链路上永远不能解码成 USER；
    // 既可能在底层 codec 因结构性约束抛错，也可能在我们的 requireUser 抛错，
    // 但都必须明确抛 IllegalArgumentException 而不能沉默接受。
    String missingMetadata =
        "{\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"sys\"}]}}";
    assertThrows(IllegalArgumentException.class, () -> codec.decode(missingMetadata));
  }

  @Test
  public void rejectsMalformedJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{not json"));
  }

  @Test
  public void rejectsNullJson() {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
  }

  @Test
  public void rejectsWrongEntryType() {
    // SessionEntryType.MESSAGE decoder rejects payloads that don't have "message" field.
    String wrongTypeJson = "{\"label\":\"oops\"}";
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongTypeJson));
    assertTrue(exception.getMessage().toLowerCase().contains("message"));
  }

  @Test
  public void rejectsEncodeOfNull() {
    assertThrows(NullPointerException.class, () -> codec.encode(null));
  }

  @Test
  public void encodesUserOnlyViaMessageEntryPayload() {
    AgentMessage user =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi")));
    String json = codec.encode(user);
    assertTrue(json.contains("\"role\":\"USER\""));
    assertTrue(json.contains("\"text\":\"hi\""));
    assertTrue(json.contains("\"assistantMetadata\":null"));
  }

  @Test
  public void multipleRoundTripsAreStable() {
    AgentMessage original =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello world")));
    String first = codec.encode(original);
    AgentMessage mid = codec.decode(first);
    String second = codec.encode(mid);
    assertEquals(first, second);
  }
}
