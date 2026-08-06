package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** EnvironmentId canonical lowercase UUID invariants. */
class EnvironmentIdTest {

  private static final String CANONICAL = "123e4567-e89b-12d3-a456-426614174000";
  private static final String OTHER = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

  @Test
  void acceptsCanonicalLowercaseUuidAndExposesCanonicalValue() {
    EnvironmentId id = new EnvironmentId(CANONICAL);

    assertEquals(CANONICAL, id.value());
    assertEquals(CANONICAL, id.toString());
    assertEquals(new EnvironmentId(CANONICAL), id);
    assertEquals(new EnvironmentId(CANONICAL).hashCode(), id.hashCode());
    assertEquals(
        "00000000-0000-0000-0000-000000000001",
        new EnvironmentId("00000000-0000-0000-0000-000000000001").value());
  }

  @Test
  void rejectsNullBlankAndSurroundingWhitespace() {
    assertThrows(NullPointerException.class, () -> new EnvironmentId(null));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId(""));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("   "));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("  " + CANONICAL + "  "));
  }

  @Test
  void rejectsNonCanonicalUuidText() {
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId(CANONICAL.toUpperCase()));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("1-1-1-1-1"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("{" + CANONICAL + "}"));
    assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("urn:uuid:" + CANONICAL));
  }

  @Test
  void rejectsInvalidUuidText() {
    IllegalArgumentException shortText =
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentId("not-a-uuid"));
    IllegalArgumentException invalidHex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EnvironmentId("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"));

    assertTrue(shortText.getMessage().contains("environmentId"));
    assertTrue(invalidHex.getMessage().contains("environmentId"));
  }

  @Test
  void rejectsNilUuid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentId("00000000-0000-0000-0000-000000000000"));
  }

  @Test
  void valueSemanticsDependOnCanonicalText() {
    EnvironmentId first = new EnvironmentId(CANONICAL);
    EnvironmentId second = new EnvironmentId(OTHER);

    assertNotEquals(first, second);
    assertEquals(first, new EnvironmentId(first.value()));
  }
}
