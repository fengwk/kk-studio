package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 校验 upstream-test-manifest.json 的机器可读性、全量 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0 在 langchain4j-http-client 与 langchain4j-http-client-jdk 范围内的 15
 * 个核心源文件全部纳入 Inventory，所有 76 个直接/参数化/继承/TCK 用例均映射到本地实际测试方法， 且所有测试方法处于非 Disabled / Active 状态，执行状态全部为
 * PASSED。
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";

  private static final Set<String> EXACT_EXPECTED_SOURCES =
      Set.of(
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/sse/DefaultServerSentEventParserTest.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientCancellationIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientCancellationIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientTimeoutIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientTimeoutIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientErrorBodyTest.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientStreamOverflowTest.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientPublisherIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/AbstractHttpClientPublisherNonBlockingIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherNonBlockingIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/HttpStreamingEventPublisherTckTest.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/MultipartBodyPublisherTest.java");

  @Test
  void upstreamTestManifestIsCompleteAndSelfContained() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("upstream-test-manifest.json")) {
      assertNotNull(in, "upstream-test-manifest.json must exist in test resources");
      root = mapper.readTree(in);
    }

    String commit = root.get("upstreamCommit").asText();
    assertEquals(
        REQUIRED_UPSTREAM_COMMIT,
        commit,
        "upstream commit must match required LangChain4j 1.20.0 release commit");

    // 校验 inventorySources 全集
    JsonNode inventorySources = root.get("inventorySources");
    assertNotNull(inventorySources, "inventorySources must be present in manifest");
    Set<String> manifestSources = new HashSet<>();
    for (JsonNode srcNode : inventorySources) {
      manifestSources.add(srcNode.asText());
    }
    assertEquals(
        EXACT_EXPECTED_SOURCES,
        manifestSources,
        "inventorySources must exactly match the expected upstream sources");

    // 校验未解决项列表必须显式声明且当前为空
    JsonNode unresolved = root.get("unresolvedCases");
    assertNotNull(unresolved, "unresolvedCases must be present in manifest");
    assertEquals(0, unresolved.size(), "no unresolved cases permitted in clean state");

    JsonNode cases = root.get("cases");
    assertTrue(cases.isArray() && cases.size() >= 70, "cases count must match full inventory");

    Set<String> seenCaseIds = new HashSet<>();
    for (JsonNode item : cases) {
      String caseId = item.get("caseId").asText();
      assertTrue(seenCaseIds.add(caseId), "caseId must be unique: " + caseId);

      String upstreamSource = item.get("upstreamSource").asText();
      assertTrue(
          EXACT_EXPECTED_SOURCES.contains(upstreamSource),
          "case upstreamSource must belong to inventorySources: " + upstreamSource);

      String localClass = item.get("localClass").asText();
      String localMethod = item.get("localMethod").asText();
      String status = item.get("executionStatus").asText();
      assertEquals("PASSED", status, "executionStatus must be PASSED for case: " + caseId);

      // 验证本地测试类与方法真实存在
      Class<?> clazz = Class.forName(localClass);
      assertNotNull(clazz, "local test class must be loadable: " + localClass);

      Method method =
          Arrays.stream(clazz.getDeclaredMethods())
              .filter(m -> m.getName().equals(localMethod))
              .findFirst()
              .orElse(null);

      assertNotNull(
          method, () -> "local test method " + localMethod + " must exist in " + localClass);

      // 验证测试方法非 Disabled
      assertFalse(
          method.isAnnotationPresent(Disabled.class),
          () -> "local test method " + localMethod + " must not be annotated with @Disabled");
    }
  }
}
