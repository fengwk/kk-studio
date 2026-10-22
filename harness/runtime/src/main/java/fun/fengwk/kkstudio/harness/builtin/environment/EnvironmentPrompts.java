package fun.fengwk.kkstudio.harness.builtin.environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载环境能力工具的 Prompt Markdown 资源。 */
public final class EnvironmentPrompts {

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/harness/builtin/environment/prompts/";

  private EnvironmentPrompts() {}

  public static String load(String fileName) {
    Objects.requireNonNull(fileName, "fileName");
    String resource = RESOURCE_PREFIX + fileName;
    try (InputStream input = EnvironmentPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing Environment tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load Environment tool prompt: " + resource, error);
    }
  }
}
