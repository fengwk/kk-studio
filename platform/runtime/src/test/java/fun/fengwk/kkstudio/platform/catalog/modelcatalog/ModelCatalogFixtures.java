package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 目录测试共享的固定资源读取与断言辅助；不接触网络。 */
final class ModelCatalogFixtures {

  private ModelCatalogFixtures() {}

  static String resource(String name) {
    try (InputStream input =
        ModelCatalogFixtures.class.getResourceAsStream(
            "/fun/fengwk/kkstudio/platform/catalog/modelcatalog/" + name)) {
      if (input == null) {
        throw new IllegalArgumentException("missing catalog fixture: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  static Map<String, ModelDefaultCandidateDTO> index(List<ModelDefaultCandidateDTO> candidates) {
    Map<String, ModelDefaultCandidateDTO> index = new LinkedHashMap<>();
    for (ModelDefaultCandidateDTO candidate : candidates) {
      String key = candidate.getProvider() + "/" + candidate.getModelId();
      if (index.put(key, candidate) != null) {
        throw new AssertionError("duplicate catalog candidate: " + key);
      }
    }
    return index;
  }

  static void assertDecimal(String expected, BigDecimal actual) {
    if (actual == null || new BigDecimal(expected).compareTo(actual) != 0) {
      throw new AssertionError("expected decimal " + expected + " but was " + actual);
    }
  }
}
