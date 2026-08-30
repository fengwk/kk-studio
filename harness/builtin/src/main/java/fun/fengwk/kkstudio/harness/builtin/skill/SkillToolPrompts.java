package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载随 builtin 打包的静态 skill Tool prompt / schema 资源。 */
final class SkillToolPrompts {

  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private SkillToolPrompts() {}

  static String load(String resourceName) {
    return read(resourceName).trim();
  }

  static InputSchema schema(String resourceName) {
    return CODEC.decode(read(resourceName));
  }

  private static String read(String resourceName) {
    String resource = "prompts/" + Objects.requireNonNull(resourceName, "resourceName");
    try (InputStream input = SkillToolPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing skill tool prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("failed to load skill tool prompt: " + resource, error);
    }
  }
}
