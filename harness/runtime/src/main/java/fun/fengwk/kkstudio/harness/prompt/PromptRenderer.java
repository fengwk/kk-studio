package fun.fengwk.kkstudio.harness.prompt;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 编译并缓存 classpath Markdown 模板；JSON schema 等非模板资源可原样读取。 */
public final class PromptRenderer {

  private static final Mustache.Compiler COMPILER =
      Mustache.compiler().strictSections(true).withEscaper(PromptRenderer::escapeXml);
  private static final Map<String, Template> TEMPLATES = new ConcurrentHashMap<>();

  private PromptRenderer() {}

  public static String render(String resource, Map<String, ?> values) {
    Objects.requireNonNull(values, "values");
    return TEMPLATES
        .computeIfAbsent(resource, name -> COMPILER.compile(read(name)))
        .execute(values);
  }

  public static String read(String resource) {
    Objects.requireNonNull(resource, "resource");
    if (resource.isBlank()) {
      throw new IllegalArgumentException("resource must not be blank");
    }
    try (InputStream input = PromptRenderer.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalArgumentException("missing classpath resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read classpath resource: " + resource, error);
    }
  }

  private static String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
