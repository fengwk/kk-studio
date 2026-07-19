package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads static MIT tool prompt assets packaged with the Daemon coding tools. */
final class CodingToolPrompts {

  private CodingToolPrompts() {}

  static String load(String toolName) {
    String resource = "prompts/" + Objects.requireNonNull(toolName, "toolName") + ".md";
    try (InputStream input = CodingToolPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing coding tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load coding tool prompt: " + resource, error);
    }
  }
}
