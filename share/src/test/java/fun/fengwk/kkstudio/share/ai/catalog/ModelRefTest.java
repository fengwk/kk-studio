package fun.fengwk.kkstudio.share.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelRefTest {

  @Test
  void splitsAtTheFirstSlashAndFormatsCanonically() {
    ModelRef ref = ModelRef.parse("provider/model/with/slash");
    assertEquals("provider", ref.providerName());
    assertEquals("model/with/slash", ref.modelName());
    assertEquals("provider/model/with/slash", ref.toString());
  }

  @Test
  void formatsCanonicalPartsWithoutWhitespace() {
    assertEquals("provider/model", new ModelRef("provider", "model").toString());
    assertEquals("provider/model", ModelRef.parse("provider/model").toString());
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse(" provider/model"));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse("provider/model "));
  }

  @Test
  void rejectsNullBlankMissingAndIncompleteParts() {
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse(null));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse(""));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse(" "));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse("provider"));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse("/model"));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse("provider/"));
    assertThrows(IllegalArgumentException.class, () -> ModelRef.parse("provider/ "));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef(null, "model"));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef("provider", null));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef(" ", "model"));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef("provider", " "));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef("provider/name", "model"));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef("\tprovider\t", "model"));
    assertThrows(IllegalArgumentException.class, () -> new ModelRef("provider", "\u2003model"));
  }
}
