package fun.fengwk.kkstudio.platform.cloudfs.tool;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Cloud 工具 prompt 与 JSON schema 资源加载器。 */
final class CloudToolPrompts {

  private static final String RESOURCE_PREFIX =
      "/fun/fengwk/kkstudio/platform/cloudfs/tool/prompts/";
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private CloudToolPrompts() {}

  static String prompt(String toolName) {
    return loadText(toolName + ".md");
  }

  static InputSchema schema(String toolName) {
    return CODEC.decode(loadText(toolName + ".schema.json"));
  }

  private static String loadText(String fileName) {
    Objects.requireNonNull(fileName, "fileName");
    String resource = RESOURCE_PREFIX + fileName;
    try (InputStream input = CloudToolPrompts.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Missing Cloud tool resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException error) {
      throw new UncheckedIOException("Failed to load Cloud tool resource: " + resource, error);
    }
  }
}
