package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canvas Function model key 使用 provider canonical token，而不是只允许 kebab-case。 */
class CanvasFunctionModelTest {

  @Test
  void acceptsCanonicalLowercaseProviderTokens() {
    assertDoesNotThrow(() -> model("gpt-image-2"));
    assertDoesNotThrow(() -> model("seedance2.0"));
    assertDoesNotThrow(() -> model("seedance2.0_vip"));
  }

  @Test
  void rejectsEmptySegmentsUppercaseWhitespaceAndEdgeSeparators() {
    for (String key :
        List.of(
            ".seedance2",
            "seedance2.",
            "seedance..2",
            "seedance__2",
            "seedance--2",
            "Seedance2",
            "seedance 2",
            " seedance2",
            "seedance2 ")) {
      assertThrows(IllegalArgumentException.class, () -> model(key), key);
    }
  }

  private static CanvasFunctionModel model(String key) {
    return new CanvasFunctionModel(
        key,
        "Model",
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
        List.of());
  }
}
