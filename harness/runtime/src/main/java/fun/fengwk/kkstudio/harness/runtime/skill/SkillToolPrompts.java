package fun.fengwk.kkstudio.harness.runtime.skill;

import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载随 runtime 打包的静态 skill Tool prompt / schema 资源。 */
final class SkillToolPrompts {

  private static final ToolDescriptorJsonCodec CODEC = new ToolDescriptorJsonCodec();

  private SkillToolPrompts() {}

  static String load(String resourceName) {
    return read(resourceName).trim();
  }

  static ToolParamsSchema schema(String resourceName) {
    return CODEC.decodeInputSchema(read(resourceName));
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
