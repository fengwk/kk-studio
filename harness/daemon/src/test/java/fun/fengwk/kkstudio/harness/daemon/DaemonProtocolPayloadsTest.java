package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;

import java.math.BigInteger;

/** 意图：WELCOME 与运行时控制帧共享的整数字段读取是 fail-closed 的——缺失、非整数、越界与非正一律是协议错误，且诊断点名帧与字段。 */
class DaemonProtocolPayloadsTest {

  private static final String FRAME = "TEMPORARY_RESOURCE_POLICY";
  private static final String FIELD = "temporaryResourceTtlSeconds";

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void readsAPositiveIntegralValue() {
    ObjectNode payload = mapper.createObjectNode().put(FIELD, 3600L);

    assertEquals(3600L, DaemonProtocolPayloads.requiredPositiveLong(payload, FRAME, FIELD));
  }

  @Test
  void rejectsAMissingField() {
    ObjectNode payload = mapper.createObjectNode();

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> DaemonProtocolPayloads.requiredPositiveLong(payload, FRAME, FIELD));
    assertDiagnostic(error);
  }

  @Test
  void rejectsANonIntegralValue() {
    ObjectNode payload = mapper.createObjectNode().put(FIELD, "3600");

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> DaemonProtocolPayloads.requiredPositiveLong(payload, FRAME, FIELD));
    assertDiagnostic(error);
  }

  @Test
  void rejectsAFractionalValue() {
    ObjectNode payload = mapper.createObjectNode().put(FIELD, 1.5d);

    assertThrows(
        DaemonProtocolException.class,
        () -> DaemonProtocolPayloads.requiredPositiveLong(payload, FRAME, FIELD));
  }

  @Test
  void rejectsAValueOutsideTheLongRange() {
    ObjectNode payload =
        mapper.createObjectNode().put(FIELD, new BigInteger("99999999999999999999999999"));

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> DaemonProtocolPayloads.requiredPositiveLong(payload, FRAME, FIELD));
    assertDiagnostic(error);
  }

  @Test
  void rejectsZeroAndNegativeValues() {
    ObjectNode zero = mapper.createObjectNode().put(FIELD, 0L);
    ObjectNode negative = mapper.createObjectNode().put(FIELD, -1L);

    assertThrows(
        DaemonProtocolException.class,
        () -> DaemonProtocolPayloads.requiredPositiveLong(zero, FRAME, FIELD));
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> DaemonProtocolPayloads.requiredPositiveLong(negative, FRAME, FIELD));
    assertTrue(error.getMessage().contains("must be positive"), error.getMessage());
  }

  /** 诊断必须点名帧与字段，且不携带任何载荷内容。 */
  private static void assertDiagnostic(DaemonProtocolException error) {
    assertTrue(error.getMessage().contains(FRAME), error.getMessage());
    assertTrue(error.getMessage().contains(FIELD), error.getMessage());
  }
}
