package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 校验 OpenAI Responses upstream-test-manifest.json 的机器可读性、全量上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0 在 langchain4j-open-ai 模块与 Responses 相关的全部 15 个测试文件、追踪继承核心基座方法后共计 165
 * 个真实测试方法全量可审计：
 *
 * <ul>
 *   <li>源文件、类与方法自洽性：精确枚举 15 个测试源文件与 165 个测试方法全集，拒绝虚构、重复或遗漏。
 *   <li>状态诚实与无虚假对等：精确维护 53 项适用 invocation 为 PORTED 与 PASSED，全部 targetTest 经反射机制验证真实存在且带
 *       &#64;Test/&#64;ParameterizedTest 注解且无 &#64;Disabled；0 项待处理（PORT_PENDING）；全部 112 项
 *       OUT_OF_SCOPE 详述架构不匹配原因；拒绝用虚假同名测试建立不合规对等。
 *   <li>类路径测试夹具校验：所有 PORTED 声明的 fixture 资源均通过相对路径检查、防止路径穿越、在类路径下真实非空存在，且 JSON 格式完整无尾随内容。
 *   <li>能力不匹配（mismatch）显式化：全部 112 项 OUT_OF_SCOPE 均详述具体架构不匹配原因，其 targetTest 严格为 null。
 *   <li>真实凭据隔离正交性：93 项依赖真实凭据（&#64;EnabledIfEnvironmentVariable）的集成测试独立标为
 *       NOT_EXECUTED_REQUIRES_CREDENTIAL，正交独立于 mappingStatus。
 *   <li>动态断言：所有统计指标均由测试动态遍历计算并断言，不单纯信任 summary 自报。
 * </ul>
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";
  private static final String REQUIRED_UPSTREAM_VERSION = "1.20.0";

  private static final int EXPECTED_TOTAL_SOURCES = 15;
  private static final int EXPECTED_TOTAL_METHODS = 165;
  private static final int EXPECTED_TOTAL_INVOCATIONS = 165;
  private static final int EXPECTED_PORTED_INVOCATIONS = 53;
  private static final int EXPECTED_IN_SCOPE_PENDING_INVOCATIONS = 0;
  private static final int EXPECTED_OUT_OF_SCOPE_INVOCATIONS = 112;
  private static final int EXPECTED_REAL_CREDENTIAL_INVOCATIONS = 93;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TARGET_CLASS_PREFIX =
      "fun.fengwk.kkstudio.harness.provider.openai.responses.";
  private static final String CLASSPATH_FIXTURE_PREFIX = "/fun/fengwk/kkstudio/harness/provider/";

  private static final Map<String, Boolean> VALIDATED_TARGETS = new HashMap<>();
  private static final Map<String, Boolean> VALIDATED_FIXTURES = new HashMap<>();

  private static void validateTargetTest(String targetTest) {
    assertNotNull(targetTest, "PORTED must have targetTest");
    assertFalse(targetTest.isBlank(), "targetTest must not be blank");

    int hashIndex = targetTest.indexOf('#');
    assertTrue(
        hashIndex > 0 && hashIndex < targetTest.length() - 1,
        () -> "targetTest must follow class#method format: " + targetTest);
    assertEquals(
        -1,
        targetTest.indexOf('#', hashIndex + 1),
        () -> "targetTest must contain exactly one '#': " + targetTest);

    String targetClassName = targetTest.substring(0, hashIndex).trim();
    String targetMethodName = targetTest.substring(hashIndex + 1).trim();
    assertFalse(
        targetClassName.isBlank(), () -> "targetClassName must not be blank: " + targetTest);
    assertFalse(
        targetMethodName.isBlank(), () -> "targetMethodName must not be blank: " + targetTest);
    assertTrue(
        targetClassName.startsWith(TARGET_CLASS_PREFIX),
        () -> "targetTest class must start with " + TARGET_CLASS_PREFIX + ": " + targetTest);

    if (VALIDATED_TARGETS.containsKey(targetTest)) {
      return;
    }

    Class<?> targetClass;
    try {
      targetClass = Class.forName(targetClassName);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(
          "targetTest class not found: " + targetClassName + " in " + targetTest, e);
    }

    assertFalse(
        targetClass.isAnnotationPresent(Disabled.class),
        () -> "targetTest class must not be annotated with @Disabled: " + targetClassName);

    boolean foundAnnotatedMethod = false;
    for (Method method : targetClass.getDeclaredMethods()) {
      if (method.getName().equals(targetMethodName)) {
        assertFalse(
            method.isAnnotationPresent(Disabled.class),
            () -> "targetTest method must not be annotated with @Disabled: " + targetMethodName);
        if (method.isAnnotationPresent(Test.class)
            || method.isAnnotationPresent(ParameterizedTest.class)) {
          foundAnnotatedMethod = true;
          break;
        }
      }
    }

    assertTrue(
        foundAnnotatedMethod,
        () ->
            "targetTest method '"
                + targetMethodName
                + "' on class '"
                + targetClassName
                + "' must exist and be annotated with @Test or @ParameterizedTest");

    VALIDATED_TARGETS.put(targetTest, Boolean.TRUE);
  }

  private static void validateFixture(String fixture) {
    assertNotNull(fixture, "PORTED fixture must not be null");
    assertFalse(fixture.isBlank(), "PORTED fixture must not be blank");
    assertFalse(
        fixture.startsWith("/"),
        () -> "PORTED fixture must be relative (cannot start with /): " + fixture);
    assertTrue(
        fixture.startsWith("openai/responses/fixtures/"),
        () -> "PORTED fixture must start with openai/responses/fixtures/: " + fixture);
    assertFalse(fixture.contains(".."), () -> "PORTED fixture must not contain '..': " + fixture);

    if (VALIDATED_FIXTURES.containsKey(fixture)) {
      return;
    }

    String fullClasspath = CLASSPATH_FIXTURE_PREFIX + fixture;
    try (InputStream stream = UpstreamTestManifestTest.class.getResourceAsStream(fullClasspath)) {
      assertNotNull(stream, () -> "PORTED fixture not found on classpath: " + fullClasspath);
      byte[] bytes = stream.readAllBytes();
      assertTrue(
          bytes.length > 0, () -> "PORTED fixture must not be empty (0 bytes): " + fullClasspath);

      if (fixture.endsWith(".json")) {
        try (JsonParser parser = MAPPER.getFactory().createParser(bytes)) {
          JsonNode node = MAPPER.readTree(parser);
          assertNotNull(node, () -> "Parsed fixture JSON must not be null: " + fullClasspath);
          assertNull(
              parser.nextToken(),
              () -> "Fixture JSON contains trailing content after root token: " + fullClasspath);
        }
      }
    } catch (IOException e) {
      throw new AssertionError(
          "Failed to read or parse fixture resource: " + fullClasspath + " - " + e.getMessage(), e);
    }

    VALIDATED_FIXTURES.put(fixture, Boolean.TRUE);
  }

  @Test
  void test_upstreamManifestHonestyAndCompleteness() throws Exception {
    InputStream stream =
        getClass()
            .getResourceAsStream(
                "/fun/fengwk/kkstudio/harness/provider/openai/responses/upstream-test-manifest.json");
    assertNotNull(stream, "upstream-test-manifest.json must exist in classpath");

    JsonNode root = MAPPER.readTree(stream);

    // 1. Identity & Commit Checks
    assertEquals(REQUIRED_UPSTREAM_COMMIT, root.path("upstreamCommit").asText());
    assertEquals(REQUIRED_UPSTREAM_VERSION, root.path("upstreamVersion").asText());
    assertEquals("Apache-2.0", root.path("license").asText());

    // 2. Summary Node Verification
    JsonNode summary = root.path("summary");
    assertEquals(EXPECTED_TOTAL_SOURCES, summary.path("totalSourceFiles").asInt());
    assertEquals(EXPECTED_TOTAL_METHODS, summary.path("totalMethods").asInt());
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, summary.path("totalInvocations").asInt());
    assertEquals(EXPECTED_PORTED_INVOCATIONS, summary.path("portedInvocations").asInt());
    assertEquals(
        EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, summary.path("inScopePendingInvocations").asInt());
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, summary.path("outOfScopeInvocations").asInt());
    assertEquals(
        EXPECTED_REAL_CREDENTIAL_INVOCATIONS, summary.path("realCredentialInvocations").asInt());

    // 3. Methods & Invocations Traversal
    JsonNode methods = root.path("methods");
    assertTrue(methods.isArray(), "methods must be a JSON array");

    Set<String> observedSourceFiles = new HashSet<>();
    Set<String> observedInvocationIds = new HashSet<>();

    int calcTotalMethods = 0;
    int calcTotalInvocations = 0;
    int calcPorted = 0;
    int calcPending = 0;
    int calcOutOfScope = 0;
    int calcRealCred = 0;

    for (JsonNode methodEntry : methods) {
      calcTotalMethods++;

      String sourcePath = methodEntry.path("sourcePath").asText();
      assertFalse(sourcePath.isBlank(), "sourcePath must not be blank");
      observedSourceFiles.add(sourcePath);

      String className = methodEntry.path("className").asText();
      assertFalse(className.isBlank(), "className must not be blank");

      String methodName = methodEntry.path("methodName").asText();
      assertFalse(methodName.isBlank(), "methodName must not be blank");

      JsonNode invocations = methodEntry.path("invocations");
      assertTrue(
          invocations.isArray() && !invocations.isEmpty(),
          () -> "invocations must be non-empty array: " + methodName);

      for (JsonNode inv : invocations) {
        calcTotalInvocations++;

        String invId = inv.path("invocationId").asText();
        assertFalse(invId.isBlank(), "invocationId must not be blank");
        assertTrue(
            observedInvocationIds.add(invId), () -> "Duplicate invocationId detected: " + invId);

        assertEquals(sourcePath, inv.path("sourcePath").asText());
        assertEquals(className, inv.path("className").asText());
        assertEquals(methodName, inv.path("methodName").asText());

        String mappingStatus = inv.path("mappingStatus").asText();
        String execStatus = inv.path("executionStatus").asText();
        String realStatus =
            inv.hasNonNull("realInteropStatus") ? inv.path("realInteropStatus").asText() : null;

        if ("PORTED".equals(mappingStatus)) {
          calcPorted++;
          assertEquals("PASSED", execStatus);
          assertNull(
              inv.hasNonNull("capabilityMismatch") ? inv.path("capabilityMismatch").asText() : null,
              "PORTED must not declare capabilityMismatch");

          String targetTest = inv.path("targetTest").asText(null);
          validateTargetTest(targetTest);

          String fixture = inv.path("fixture").asText(null);
          validateFixture(fixture);
        } else if ("OUT_OF_SCOPE".equals(mappingStatus)) {
          calcOutOfScope++;
          assertEquals("NOT_EXECUTED_OUT_OF_SCOPE", execStatus);
          assertNull(
              inv.hasNonNull("targetTest") ? inv.path("targetTest").asText() : null,
              "OUT_OF_SCOPE must not declare targetTest");
          assertNull(
              inv.hasNonNull("fixture") ? inv.path("fixture").asText() : null,
              "OUT_OF_SCOPE must not declare fixture");

          String mismatch = inv.path("capabilityMismatch").asText(null);
          assertNotNull(mismatch, () -> "OUT_OF_SCOPE must provide capabilityMismatch: " + invId);
          assertFalse(mismatch.isBlank(), () -> "capabilityMismatch must not be blank: " + invId);
        } else if ("PORT_PENDING".equals(mappingStatus)) {
          calcPending++;
        } else {
          throw new AssertionError("Unknown mappingStatus: " + mappingStatus);
        }

        if ("NOT_EXECUTED_REQUIRES_CREDENTIAL".equals(realStatus)) {
          calcRealCred++;
          assertTrue(
              inv.hasNonNull("condition"),
              () -> "NOT_EXECUTED_REQUIRES_CREDENTIAL must specify condition: " + invId);
        }
      }
    }

    // 4. Dynamic Count Assertions
    assertEquals(EXPECTED_TOTAL_SOURCES, observedSourceFiles.size());
    assertEquals(EXPECTED_TOTAL_METHODS, calcTotalMethods);
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, calcTotalInvocations);
    assertEquals(EXPECTED_PORTED_INVOCATIONS, calcPorted);
    assertEquals(EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, calcPending);
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, calcOutOfScope);
    assertEquals(EXPECTED_REAL_CREDENTIAL_INVOCATIONS, calcRealCred);
  }
}
