package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;

import java.time.Instant;

/** 覆盖 not-required、undecided 和 decided 三种状态的 approval 持久化 wire。 */
class ToolApprovalJsonCodecTest {

  private static final Instant REQUESTED_AT = Instant.parse("2026-08-04T00:00:00Z");
  private static final Instant DECIDED_AT = Instant.parse("2026-08-04T00:00:01.123456Z");

  private final ToolApprovalJsonCodec codec = new ToolApprovalJsonCodec();

  /** 精确 JSON 既证明每个 nullable 字段都显式，也证明时间戳保留 ISO 精度。 */
  @Test
  void roundTripsEveryApprovalStateWithExactJson() {
    ToolApproval notRequired = ToolApproval.notRequired();
    assertEquals(
        "{\"required\":false,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":null,\"decidedAt\":null}",
        codec.encode(notRequired));
    assertEquals(notRequired, codec.decode(codec.encode(notRequired)));

    ToolApproval undecided = ToolApproval.request(REQUESTED_AT, "Needs review");
    assertEquals(
        "{\"required\":true,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":\"Needs review\",\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":null}",
        codec.encode(undecided));
    assertEquals(undecided, codec.decode(codec.encode(undecided)));
    assertNull(codec.decode(codec.encode(undecided)).decision());

    ToolApproval decided =
        undecided.decide(
            ToolApprovalDecision.ALLOWED, "decision-1", "alice", "Approved", DECIDED_AT);
    assertEquals(
        "{\"required\":true,\"decision\":\"ALLOWED\",\"decisionId\":\"decision-1\","
            + "\"actor\":\"alice\",\"reason\":\"Approved\","
            + "\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:01.123456Z\"}",
        codec.encode(decided));
    assertEquals(decided, codec.decode(codec.encode(decided)));
    assertEquals(decided, codec.decodeNode(codec.encodeNode(decided)));
  }

  /** 严格 parser 设置独立于 approval 语义拒绝畸形文档。 */
  @Test
  void rejectsMalformedDocumentBoundaries() {
    String json = codec.encode(ToolApproval.notRequired());
    String duplicate = json.replace("\"required\":false", "\"required\":false,\"required\":true");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 精确字段、enum/Instant 语法以及完整的 approval 矩阵都被重新校验。 */
  @Test
  void rejectsCorruptApprovalFacts() {
    assertInvalid(
        "{\"required\":false,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":null,\"decidedAt\":null,\"extra\":1}");
    assertInvalid(
        "{\"required\":false,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":null}");
    assertInvalid(
        "{\"required\":\"false\",\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":null,\"decidedAt\":null}");
    assertInvalid(
        "{\"required\":true,\"decision\":\"OTHER\",\"decisionId\":\"d\",\"actor\":\"a\","
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:01Z\"}");
    assertInvalid(
        "{\"required\":true,\"decision\":1,\"decisionId\":\"d\",\"actor\":\"a\","
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:01Z\"}");
    assertInvalid(
        "{\"required\":true,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":\"not-an-instant\",\"decidedAt\":null}");
    assertInvalid(
        "{\"required\":true,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":1,\"decidedAt\":null}");
    assertInvalid(
        "{\"required\":false,\"decision\":null,\"decisionId\":null,\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:00Z\",\"decidedAt\":null}");
    assertInvalid(
        "{\"required\":true,\"decision\":null,\"decisionId\":\"d\",\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:00Z\",\"decidedAt\":null}");
    assertInvalid(
        "{\"required\":true,\"decision\":\"DENIED\",\"decisionId\":\"d\",\"actor\":null,"
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:01Z\"}");
    assertInvalid(
        "{\"required\":true,\"decision\":\"DENIED\",\"decisionId\":\"d\",\"actor\":\"a\","
            + "\"reason\":\" padded \",\"requestedAt\":\"2026-08-04T00:00:00Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:01Z\"}");
    assertInvalid(
        "{\"required\":true,\"decision\":\"DENIED\",\"decisionId\":\"d\",\"actor\":\"a\","
            + "\"reason\":null,\"requestedAt\":\"2026-08-04T00:00:01Z\","
            + "\"decidedAt\":\"2026-08-04T00:00:00Z\"}");
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
  }
}
