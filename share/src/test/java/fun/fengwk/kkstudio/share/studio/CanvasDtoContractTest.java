package fun.fengwk.kkstudio.share.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/** Canvas wire DTO 契约：durable version 是规范非负十进制字符串，required-nullable 字段显式发射 null。 */
class CanvasDtoContractTest {

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "CanvasDocumentDTO.threadId",
    "CanvasResourceNodeDTO.groupId",
    "CanvasResourceNodeDTO.function",
    "CanvasResourceNodeDTO.run",
    "CanvasResourceDTO.blobId",
    "CanvasResourceDTO.textContent",
    "CanvasResourceDTO.mediaType",
    "CanvasResourceDTO.sizeBytes",
    "CanvasResourceDTO.width",
    "CanvasResourceDTO.height",
    "CanvasResourceDTO.durationMs",
    "CanvasFunctionRunDTO.error",
    "CanvasChangesDTO.snapshot",
    "CanvasFunctionModelDTO.unavailableReason",
    "CanvasFunctionParameterDefinitionDTO.defaultValue",
    "CanvasFunctionParameterDefinitionDTO.min",
    "CanvasFunctionParameterDefinitionDTO.max",
    "CanvasThreadBranchSettingsDTO.environment"
  };

  private static final String[] VERSION_STRING_FIELDS = {
    "CanvasDocumentDTO.version",
    "CanvasPatchDTO.baseVersion",
    "CanvasPatchDTO.version",
    "CanvasVersionEventDTO.version",
    "ApplyCanvasCommandsRequestDTO.expectedVersion"
  };

  @Test
  void durableVersionsAreWireDecimalStrings() throws Exception {
    for (String field : VERSION_STRING_FIELDS) {
      Field version = field(field);
      assertEquals(
          String.class,
          version.getType(),
          field + " must be String so the wire is always a decimal string");
    }
  }

  @Test
  void requiredNullableFieldsAreAlwaysIncluded() throws Exception {
    for (String field : REQUIRED_NULLABLE_FIELDS) {
      Field nullable = field(field);
      JsonInclude include = nullable.getAnnotation(JsonInclude.class);
      assertNotNull(include, field + " must declare @JsonInclude");
      assertEquals(
          JsonInclude.Include.ALWAYS,
          include.value(),
          field + " must explicitly emit null even under the global NON_NULL default");
    }
  }

  private static Field field(String ownerAndField) throws NoSuchFieldException {
    String[] parts = ownerAndField.split("\\.");
    String owner = "fun.fengwk.kkstudio.share.studio." + parts[0];
    try {
      return Class.forName(owner).getDeclaredField(parts[1]);
    } catch (ClassNotFoundException error) {
      throw new AssertionError("unknown DTO: " + owner, error);
    }
  }
}
