package fun.fengwk.kkstudio.harness.runtime.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载随 runtime 打包的静态 skill Tool prompt 资源。 */
final class SkillToolPrompts {

  private SkillToolPrompts() {}

  static String load(String resourceName) {
    String resource = "prompts/" + Objects.requireNonNull(resourceName, "resourceName");
    try (InputStream input = SkillToolPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing skill tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load skill tool prompt: " + resource, error);
    }
  }
}
