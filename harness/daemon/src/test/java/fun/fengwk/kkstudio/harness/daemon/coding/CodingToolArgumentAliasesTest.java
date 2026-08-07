package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** pi-base 的 path 参数容错只接受唯一的 filePath 别名，不放宽其余严格 schema。 */
class CodingToolArgumentAliasesTest {

  @Test
  void rewritesOnlyTheUnambiguousFilePathAliasForPathBearingTools() throws Exception {
    String normalized =
        CodingToolArgumentAliases.normalize("read", "{\"filePath\":\"src/App.java\",\"limit\":20}");
    JsonNode value = AbstractCodingTool.OBJECT_MAPPER.readTree(normalized);

    assertEquals("src/App.java", value.path("path").asText());
    assertEquals(20, value.path("limit").asInt());
    assertFalse(value.has("filePath"));
    assertEquals(
        "x",
        AbstractCodingTool.OBJECT_MAPPER
            .readTree(
                CodingToolArgumentAliases.normalize("lsp_goto_definition", "{\"filePath\":\"x\"}"))
            .path("path")
            .asText());
  }

  @Test
  void leavesAmbiguousAndNonPathToolArgumentsForStrictValidation() {
    String both = "{\"path\":\"canonical\",\"filePath\":\"legacy\"}";

    assertEquals(both, CodingToolArgumentAliases.normalize("read", both));
    assertThrows(
        IllegalArgumentException.class,
        () -> CodingToolArgumentAliases.normalize("read", "{not-json}"));
    assertThrows(
        IllegalArgumentException.class, () -> CodingToolArgumentAliases.normalize("read", "[]"));
    assertTrue(
        CodingToolArgumentAliases.normalize("bash", "{\"command\":\"true\",\"filePath\":\"x\"}")
            .contains("filePath"));
  }
}
