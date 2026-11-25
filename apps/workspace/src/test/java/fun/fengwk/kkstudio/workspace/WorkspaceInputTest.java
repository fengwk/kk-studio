package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.UUID;

class WorkspaceInputTest {
  @Test
  void acceptsOnlyCanonicalNonnegativeVersions() {
    assertEquals(0, WorkspaceInput.version("0"));
    assertEquals(Long.MAX_VALUE, WorkspaceInput.version(Long.toString(Long.MAX_VALUE)));
    for (String value : new String[] {"", "-1", "+1", "01", " 1", "9223372036854775808"}) {
      assertEquals(
          "INVALID_ARGUMENT",
          assertThrows(DomainException.class, () -> WorkspaceInput.version(value)).reason());
    }
  }

  @Test
  void acceptsOnlyCanonicalUuids() {
    var id = UUID.randomUUID();
    assertEquals(id, WorkspaceInput.uuid(id.toString()));
    for (String value : new String[] {"", id.toString().toUpperCase(), "not-a-uuid"}) {
      assertEquals(
          "INVALID_ARGUMENT",
          assertThrows(DomainException.class, () -> WorkspaceInput.uuid(value)).reason());
    }
  }

  @Test
  void requiredValuesRejectNullAndPreserveProvidedValues() {
    assertEquals("value", WorkspaceInput.required("value", "field"));
    assertEquals(
        "INVALID_ARGUMENT",
        assertThrows(DomainException.class, () -> WorkspaceInput.required(null, "field")).reason());
  }
}
