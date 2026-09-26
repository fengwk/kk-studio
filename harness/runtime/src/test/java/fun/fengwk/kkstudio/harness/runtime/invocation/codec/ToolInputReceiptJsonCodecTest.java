package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;

import java.time.Instant;
import java.util.UUID;

/** 人工输入回执的严格 JSON codec：往返稳定、字段齐备、拒绝未知或非法字段。 */
class ToolInputReceiptJsonCodecTest {

  private static final ToolInputReceiptJsonCodec CODEC = new ToolInputReceiptJsonCodec();
  private static final UUID SUBMISSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant ACCEPTED_AT = Instant.parse("2026-09-27T00:00:00Z");

  @Test
  void roundTripsCanonicalReceipt() {
    ToolInputReceipt receipt = new ToolInputReceipt(SUBMISSION_ID, "alice", ACCEPTED_AT);

    String json = CODEC.encode(receipt);

    assertEquals(
        "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\","
            + "\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
        json);
    assertEquals(receipt, CODEC.decode(json));
  }

  @Test
  void rejectsMissingUnknownOrInvalidFields() {
    for (String malformed :
        new String[] {
          "{}",
          "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\"}",
          "{\"submissionId\":\"not-a-uuid\",\"actor\":\"alice\",\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
          "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\",\"acceptedAt\":\"2026-09-27T00:00:00Z\",\"extra\":1}",
          "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"  alice  \",\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
          "[]"
        }) {
      assertThrows(IllegalArgumentException.class, () -> CODEC.decode(malformed), malformed);
    }
    assertThrows(NullPointerException.class, () -> CODEC.encode(null));
  }

  /** 缺失字段与不可解析节点必须拒绝（回执时间由服务端推导，永不接受缺失或客户端时间）。 */
  @Test
  void rejectsMissingFieldsAndUnparsableInput() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decodeNode(
                JsonNodeFactory.instance
                    .objectNode()
                    .put("submissionId", "11111111-1111-1111-1111-111111111111")));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode("not json"));
  }
}
