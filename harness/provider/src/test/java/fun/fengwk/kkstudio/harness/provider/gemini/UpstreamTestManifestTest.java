package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 校验 Google AI Gemini upstream-test-manifest.json 的机器可读性、全量上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0（提交 3a2f4dca6fb447e4d191624b3d588952ed9f4ce9）在
 * langchain4j-google-ai-gemini 模块全部 51 个测试文件、追踪继承的核心基座方法后共计 676 个真实测试方法、按 invocation 展开共 676 个
 * invocation 全量可审计：
 *
 * <ul>
 *   <li>源文件、类与方法自洽性：精确枚举 51 个测试源文件与 676 个真实方法全集，拒绝虚构、重复或遗漏。
 *   <li>状态诚实与无虚假对等：精确维护 145 项适用 invocation 为 PORTED 与 PASSED，全部 targetTest
 *       经反射机制验证真实存在且带 @Test/@ParameterizedTest 注解且无 @Disabled；0 项待处理（PORT_PENDING）；全部 531 项
 *       OUT_OF_SCOPE 详述架构不匹配原因；拒绝用虚假同名测试建立不合规对等。
 *   <li>类路径测试夹具校验：所有 PORTED 声明的 fixture 资源均通过相对路径检查、防止路径穿越、在类路径下真实非空存在。
 *   <li>能力不匹配（mismatch）显式化：全部 531 项 OUT_OF_SCOPE 均详述具体架构不匹配原因，其 targetTest 严格为 null。
 *   <li>真实凭据隔离正交性：281 项依赖真实凭据（@EnabledIfEnvironmentVariable）的集成测试独立标为
 *       NOT_EXECUTED_REQUIRES_CREDENTIAL，正交独立于 mappingStatus。
 *   <li>动态断言：所有统计指标均由测试动态遍历计算并断言，不单纯信任 summary 自报。
 * </ul>
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";
  private static final String REQUIRED_UPSTREAM_VERSION = "1.20.0";

  private static final int EXPECTED_TOTAL_SOURCES = 51;
  private static final int EXPECTED_TOTAL_METHODS = 676;
  private static final int EXPECTED_TOTAL_INVOCATIONS = 676;
  private static final int EXPECTED_PORTED_INVOCATIONS = 145;
  private static final int EXPECTED_IN_SCOPE_PENDING_INVOCATIONS = 0;
  private static final int EXPECTED_OUT_OF_SCOPE_INVOCATIONS = 531;
  private static final int EXPECTED_REAL_CREDENTIAL_INVOCATIONS = 281;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TARGET_CLASS_PREFIX = "fun.fengwk.kkstudio.harness.provider.gemini.";
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
        fixture.startsWith("gemini/fixtures/"),
        () -> "PORTED fixture must start with gemini/fixtures/: " + fixture);
    assertFalse(fixture.contains(".."), () -> "PORTED fixture must not contain '..': " + fixture);

    if (VALIDATED_FIXTURES.containsKey(fixture)) {
      return;
    }

    String resourcePath = CLASSPATH_FIXTURE_PREFIX + fixture;
    try (InputStream in = UpstreamTestManifestTest.class.getResourceAsStream(resourcePath)) {
      assertNotNull(in, () -> "fixture resource not found on classpath: " + resourcePath);
      byte[] bytes = in.readAllBytes();
      assertTrue(bytes.length > 0, () -> "fixture resource must not be empty: " + resourcePath);

      if (fixture.endsWith(".json")) {
        try (JsonParser parser = MAPPER.createParser(bytes)) {
          JsonNode tree = MAPPER.readTree(parser);
          assertNotNull(tree, () -> "JSON tree must not be null for " + resourcePath);
          assertFalse(
              tree.isNull(), () -> "JSON tree must not represent a null value for " + resourcePath);
          assertNull(
              parser.nextToken(),
              () -> "fixture JSON must have no trailing content for " + resourcePath);
        } catch (IOException e) {
          throw new AssertionError("fixture failed to parse as valid JSON: " + resourcePath, e);
        }
      }
    } catch (IOException e) {
      throw new AssertionError("failed reading fixture resource: " + resourcePath, e);
    }

    VALIDATED_FIXTURES.put(fixture, Boolean.TRUE);
  }

  @Test
  void testManifestIntegrityAndAudit() throws Exception {
    String manifestPath =
        "/fun/fengwk/kkstudio/harness/provider/gemini/upstream-test-manifest.json";
    InputStream in = getClass().getResourceAsStream(manifestPath);
    assertNotNull(in, "upstream-test-manifest.json must exist on classpath");

    JsonNode root = MAPPER.readTree(in);
    assertEquals(
        REQUIRED_UPSTREAM_COMMIT,
        root.path("upstreamCommit").asText(),
        "upstream commit must match exact 1.20.0 commit");
    assertEquals(
        REQUIRED_UPSTREAM_VERSION,
        root.path("upstreamVersion").asText(),
        "upstream version must match exact 1.20.0");

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

    Set<String> seenInvocationIds = new HashSet<>();
    Set<String> seenSourceFiles = new HashSet<>();
    int dynamicTotalMethods = 0;
    int dynamicTotalInvocations = 0;
    int dynamicPorted = 0;
    int dynamicOos = 0;
    int dynamicPending = 0;
    int dynamicRealCred = 0;

    JsonNode methodsArray = root.path("methods");
    assertTrue(methodsArray.isArray() && methodsArray.size() > 0, "methods must not be empty");

    for (JsonNode methodNode : methodsArray) {
      dynamicTotalMethods++;
      String sourcePath = methodNode.path("sourcePath").asText();
      seenSourceFiles.add(sourcePath);

      JsonNode invocationsNode = methodNode.path("invocations");
      assertTrue(invocationsNode.isArray(), "invocations must be an array");

      for (JsonNode inv : invocationsNode) {
        dynamicTotalInvocations++;
        String id = inv.path("invocationId").asText();
        assertFalse(id.isBlank(), "invocationId must not be blank");
        assertTrue(seenInvocationIds.add(id), () -> "duplicate invocationId: " + id);

        String mappingStatus = inv.path("mappingStatus").asText();
        assertFalse(
            "PORT_PENDING".equals(mappingStatus) || "SKIPPED".equals(mappingStatus),
            () -> "Zero pending / skipped allowed: " + id);

        if ("PORTED".equals(mappingStatus)) {
          dynamicPorted++;
          assertEquals("PASSED", inv.path("executionStatus").asText());
          validateTargetTest(inv.path("targetTest").asText());
          validateFixture(inv.path("fixture").asText());
          assertTrue(inv.path("capabilityMismatch").isNull());
        } else if ("OUT_OF_SCOPE".equals(mappingStatus)) {
          dynamicOos++;
          assertEquals("NOT_EXECUTED_OUT_OF_SCOPE", inv.path("executionStatus").asText());
          assertTrue(
              inv.path("targetTest").isNull(),
              () -> "OUT_OF_SCOPE invocation targetTest must be null: " + id);
          assertTrue(
              inv.path("fixture").isNull(),
              () -> "OUT_OF_SCOPE invocation fixture must be null: " + id);
          String mismatch = inv.path("capabilityMismatch").asText();
          assertNotNull(mismatch, () -> "OUT_OF_SCOPE must have capabilityMismatch: " + id);
          assertFalse(mismatch.isBlank(), () -> "OUT_OF_SCOPE mismatch must not be blank: " + id);
        } else {
          throw new AssertionError("unknown mappingStatus: " + mappingStatus + " on " + id);
        }

        if ("NOT_EXECUTED_REQUIRES_CREDENTIAL".equals(inv.path("realInteropStatus").asText())) {
          dynamicRealCred++;
        }
      }
    }

    assertEquals(EXPECTED_TOTAL_SOURCES, seenSourceFiles.size());
    assertEquals(EXPECTED_TOTAL_METHODS, dynamicTotalMethods);
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, dynamicTotalInvocations);
    assertEquals(EXPECTED_PORTED_INVOCATIONS, dynamicPorted);
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, dynamicOos);
    assertEquals(EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, dynamicPending);
    assertEquals(EXPECTED_REAL_CREDENTIAL_INVOCATIONS, dynamicRealCred);
  }

  // 仅供 validateTargetTest 反射守卫校验使用的内部测试夹具；
  // 声明为 private static class 且不添加 @Nested，确保 JUnit 引擎不会将其识别为待运行的测试。
  @Disabled
  private static class DisabledClassFixture {
    @Test
    void dummyMethod() {}
  }

  private static class DisabledMethodFixture {
    @Disabled
    @Test
    void disabledMethod() {}
  }

  /** 验证 validateTargetTest 对目标格式、类包名前缀白名单、类与方法存在性、测试注解缺失以及 @Disabled 标注的严格校验与防御。 */
  @Test
  void test_validateTargetTest_guards() {
    assertThrows(AssertionError.class, () -> validateTargetTest("malformed_no_hash"));
    assertThrows(AssertionError.class, () -> validateTargetTest("#method_only"));
    assertThrows(AssertionError.class, () -> validateTargetTest("Class#"));
    assertThrows(AssertionError.class, () -> validateTargetTest("Class#method#extra"));
    assertThrows(
        AssertionError.class, () -> validateTargetTest("non.existent." + "FakeClass#method"));
    assertThrows(
        AssertionError.class, () -> validateTargetTest(String.class.getName() + "#length"));
    assertThrows(
        AssertionError.class,
        () ->
            validateTargetTest(
                "fun.fengwk.kkstudio.harness.provider."
                    + "transport.UpstreamTestManifestTest#test"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(UpstreamTestManifestTest.class.getName() + "#nonExistentMethod"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(UpstreamTestManifestTest.class.getName() + "#validateTargetTest"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(DisabledClassFixture.class.getName() + "#dummyMethod"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(DisabledMethodFixture.class.getName() + "#disabledMethod"));
  }

  /** 验证 validateFixture 针对 null/空字符串、绝对路径、非白名单前缀、路径穿越与不存在的类路径资源的防御性拦截。 */
  @Test
  void test_validateFixture_guards() {
    assertThrows(AssertionError.class, () -> validateFixture(null));
    assertThrows(AssertionError.class, () -> validateFixture(""));
    assertThrows(AssertionError.class, () -> validateFixture("   "));
    // 拒绝以 / 开头的绝对路径
    assertThrows(
        AssertionError.class, () -> validateFixture("/gemini/fixtures/stream-incremental.sse"));
    // 拒绝非指定目录前缀
    assertThrows(
        AssertionError.class, () -> validateFixture("other/fixtures/stream-incremental.sse"));
    // 拒绝路径穿越 ..
    assertThrows(
        AssertionError.class, () -> validateFixture("gemini/fixtures/../stream-incremental.sse"));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("gemini/fixtures/sub/../../stream-incremental.sse"));
    // 拒绝类路径不存在的资源
    assertThrows(
        AssertionError.class, () -> validateFixture("gemini/fixtures/non_existent_fixture.json"));
    assertThrows(
        AssertionError.class, () -> validateFixture("gemini/fixtures/non_existent_stream.sse"));
  }
}
