package fun.fengwk.kkstudio.harness.plugin.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 严格 classpath prompt 模板 loader：按资源路径加载原始资源并缓存解析后的 {@link PromptTemplate}。
 *
 * <p>缺失资源立即以 {@link IllegalArgumentException} 失败；同一资源路径重复加载命中缓存，不重复读取 classpath。 模板解析与渲染的严格性见
 * {@link PromptTemplate}。
 */
public final class PromptTemplateLoader {

  private final ClassLoader classLoader;
  private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

  public PromptTemplateLoader() {
    this(PromptTemplateLoader.class.getClassLoader());
  }

  public PromptTemplateLoader(ClassLoader classLoader) {
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
  }

  /** 按 classpath 资源路径加载（并缓存）模板；资源缺失或模板非法时抛 {@link IllegalArgumentException}。 */
  public PromptTemplate load(String resourcePath) {
    Objects.requireNonNull(resourcePath, "resourcePath");
    if (resourcePath.isBlank()) {
      throw new IllegalArgumentException("resourcePath must not be blank");
    }
    return cache.computeIfAbsent(resourcePath, this::read);
  }

  private PromptTemplate read(String resourcePath) {
    try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalArgumentException(
            "missing classpath prompt template resource: " + resourcePath);
      }
      String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return new PromptTemplate(resourcePath, raw);
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot read classpath prompt template resource: " + resourcePath, error);
    }
  }
}
