package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.Locale;
import java.util.UUID;

/** requestId 只接受 canonical lowercase UUID，其它形态统一收敛为 validation。 */
class CanvasFunctionRequestIdsTest {

  private static final String CANONICAL = "c0ffee00-0000-4000-8000-000000000001";

  @Test
  void acceptsOnlyCanonicalLowercaseUuid() {
    assertEquals(UUID.fromString(CANONICAL), CanvasFunctionRequestIds.validate(CANONICAL));
    for (String invalid :
        new String[] {
          null,
          "",
          "request-1",
          CANONICAL.toUpperCase(Locale.ROOT),
          "{" + CANONICAL + "}",
          "1-1-1-1-1",
          CANONICAL + "0"
        }) {
      DomainException rejected =
          assertThrows(
              DomainException.class, () -> CanvasFunctionRequestIds.validate(invalid), invalid);
      assertEquals(DomainErrorCode.VALIDATION, rejected.code());
      assertEquals("requestId", rejected.resource());
      assertEquals("requestId must be a canonical lowercase UUID", rejected.getMessage());
    }
  }
}
