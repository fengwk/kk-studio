package fun.fengwk.kkstudio.harness.runtime.goal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads static MIT goal/skill tool prompt assets packaged with the runtime. */
public final class GoalToolPrompts {

  private GoalToolPrompts() {}

  public static String load(String resourceName) {
    String resource = "prompts/" + Objects.requireNonNull(resourceName, "resourceName");
    try (InputStream input = GoalToolPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing goal tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load goal tool prompt: " + resource, error);
    }
  }
}
