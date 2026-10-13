package fun.fengwk.kkstudio.harness.common.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PromptTemplateTest {

  private static final String GREETING = "fun/fengwk/kkstudio/harness/common/prompt/greeting.txt";
  private static final String UNTERMINATED =
      "fun/fengwk/kkstudio/harness/common/prompt/unterminated.txt";
  private static final String INVALID_NAME =
      "fun/fengwk/kkstudio/harness/common/prompt/invalid-name.txt";

  private final PromptTemplateLoader loader = new PromptTemplateLoader();

  /** 验证正常从 classpath 读取并严格按变量映射进行占位符替换与渲染。 */
  @Test
  void loadsAndRendersWithExactVariables() {
    PromptTemplate template = loader.load(GREETING);
    assertEquals("Hello ${name}, your task is ${task}.\n", template.raw());
    assertEquals(List.of("name", "task"), template.variables());
    assertEquals(
        "Hello Alice, your task is write tests.\n",
        template.render(Map.of("name", "Alice", "task", "write tests")));
  }

  /** 验证同一个 Loader 实例对同一资源路径存在内存缓存，且不同 Loader 实例互不影响。 */
  @Test
  void cachesRawResourcesAcrossLoads() {
    assertSame(loader.load(GREETING), loader.load(GREETING));
    assertNotSame(loader.load(GREETING), new PromptTemplateLoader().load(GREETING));
  }

  /** 验证加载不存在或非法的资源路径时立即抛出 IllegalArgumentException。 */
  @Test
  void rejectsMissingResource() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> loader.load("fun/fengwk/kkstudio/harness/common/prompt/missing.txt"));
    assertTrue(error.getMessage().contains("missing classpath prompt template resource"));
    assertThrows(IllegalArgumentException.class, () -> loader.load(""));
    assertThrows(NullPointerException.class, () -> loader.load(null));
  }

  /** 验证模板中存在未闭合的占位符或非法字符的变量名时，在加载期解析直接拒绝。 */
  @Test
  void rejectsUnterminatedAndInvalidPlaceholdersAtLoadTime() {
    IllegalArgumentException unterminated =
        assertThrows(IllegalArgumentException.class, () -> loader.load(UNTERMINATED));
    assertTrue(unterminated.getMessage().contains("unterminated placeholder"));
    IllegalArgumentException invalid =
        assertThrows(IllegalArgumentException.class, () -> loader.load(INVALID_NAME));
    assertTrue(invalid.getMessage().contains("invalid placeholder variable name"));
  }

  /** 验证渲染时缺少声明变量（unresolved）或传入了额外变量（unexpected）时均严格拒绝。 */
  @Test
  void renderRejectsUnresolvedAndUnexpectedVariables() {
    PromptTemplate template = loader.load(GREETING);
    IllegalArgumentException unresolved =
        assertThrows(
            IllegalArgumentException.class, () -> template.render(Map.of("name", "Alice")));
    assertTrue(unresolved.getMessage().contains("unresolved placeholder ${task}"));
    IllegalArgumentException unexpected =
        assertThrows(
            IllegalArgumentException.class,
            () -> template.render(Map.of("name", "Alice", "task", "t", "extra", "x")));
    assertTrue(unexpected.getMessage().contains("unexpected variable extra"));
    Map<String, String> withNullTask = new HashMap<>();
    withNullTask.put("name", "Alice");
    withNullTask.put("task", null);
    assertThrows(IllegalArgumentException.class, () -> template.render(withNullTask));
    assertThrows(NullPointerException.class, () -> template.render(null));
  }

  /** 验证模板中重复出现的相同占位符变量被正确去重，且多处替换均生效。 */
  @Test
  void deduplicatesRepeatedPlaceholders() {
    PromptTemplate template = new PromptTemplate("inline", "repeat ${a} again ${a} and ${b} ${a}");
    assertEquals(List.of("a", "b"), template.variables());
    assertEquals("repeat 1 again 1 and 2 1", template.render(Map.of("a", "1", "b", "2")));
  }

  /** 验证变量值中如果恰好包含 ${...} 文本，不会被进行二次递归解析。 */
  @Test
  void rendersValuesWithoutSecondPassSubstitution() {
    PromptTemplate template = new PromptTemplate("inline", "${a}");
    assertEquals("${b}", template.render(Map.of("a", "${b}")));
  }

  /**
   * 验证从注入的 ClassLoader 读取资源发生 IOException 时：包装为 IllegalArgumentException 并保留 IO 根因、关闭底层流、
   * 失败结果不污染缓存（同一 loader 再次读取成功）。
   */
  @Test
  void surfacesReadFailureWithoutPoisoningCache() {
    String resource = "fun/fengwk/kkstudio/harness/common/prompt/retryable.txt";
    RetryableClassLoader classLoader = new RetryableClassLoader(resource, "Hello ${name}.\n");
    PromptTemplateLoader failingLoader = new PromptTemplateLoader(classLoader);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> failingLoader.load(resource));
    assertTrue(error.getMessage().contains("cannot read classpath prompt template resource"));
    assertTrue(error.getCause() instanceof IOException, "IO root cause must be preserved");
    assertEquals(1, classLoader.failuresDelivered, "first read must fail");
    assertTrue(classLoader.failingStreamClosed, "failing stream must be closed");
    assertEquals(
        0, classLoader.liveFailingStreams, "no read failure may leak the underlying stream");

    PromptTemplate template = failingLoader.load(resource);
    assertEquals("Hello ${name}.\n", template.raw());
    assertEquals(List.of("name"), template.variables());
    assertEquals(1, classLoader.successfulReads, "cache must not retain the failure");
  }

  /** 真实 ClassLoader 边界：首个读取返回抛 IOException 的流，之后返回正常内容；用于验证 IO 失败路径。 */
  private static final class RetryableClassLoader extends ClassLoader {

    private final String resource;
    private final String content;
    private boolean failNext = true;
    private int failuresDelivered;
    private int liveFailingStreams;
    private boolean failingStreamClosed;
    private int successfulReads;

    RetryableClassLoader(String resource, String content) {
      this.resource = resource;
      this.content = content;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      if (!resource.equals(name)) {
        return null;
      }
      if (failNext) {
        failNext = false;
        failuresDelivered++;
        liveFailingStreams++;
        return new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("simulated prompt template read failure");
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("simulated prompt template read failure");
          }

          @Override
          public void close() {
            failingStreamClosed = true;
            liveFailingStreams--;
          }
        };
      }
      successfulReads++;
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
  }
}
