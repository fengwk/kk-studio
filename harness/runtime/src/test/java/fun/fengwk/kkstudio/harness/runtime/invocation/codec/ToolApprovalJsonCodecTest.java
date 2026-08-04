package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;

import java.time.Instant;

/** Durable approval wire for not-required, undecided and decided states. */
class ToolApprovalJsonCodecTest {

  private static final Instant REQUESTED_AT = Instant.parse("2026-08-04T00:00:00Z");
  private static final Instant DECIDED_AT = Instant.parse("2026-08-04T00:00:01.123456Z");

  private final ToolApprovalJsonCodec codec = new ToolApprovalJsonCodec();

  /** Exact JSON proves every nullable fact is explicit and timestamps retain ISO precision. */
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

  /** Strict parser settings reject malformed documents independently of approval semantics. */
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

  /** Exact fields, enum/Instant syntax and the complete approval matrix are revalidated. */
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
