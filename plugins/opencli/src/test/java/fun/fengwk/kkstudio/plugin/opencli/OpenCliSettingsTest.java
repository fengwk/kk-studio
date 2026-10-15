package fun.fengwk.kkstudio.plugin.opencli;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.LongFunction;

/** {@link OpenCliSettings} 及其嵌套 record 的配置解析与原范围约束单元测试。 */
class OpenCliSettingsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final OpenCliSettings.GptImage2 DEFAULT_GPT = OpenCliSettings.GptImage2.DEFAULT;
  private static final OpenCliSettings.Seedance DEFAULT_SEEDANCE = OpenCliSettings.Seedance.DEFAULT;

  @Test
  void defaultValuesMatchBaseline() {
    OpenCliSettings s = OpenCliSettings.DEFAULT;
    assertFalse(s.enabled());
    assertNull(s.baseUrl());
    assertNull(s.instanceId());
    assertEquals(5000L, s.connectTimeoutMillis());
    assertEquals(120000L, s.requestTimeoutMillis());
    assertEquals(130000L, s.longPollTimeoutMillis());
    assertEquals(16384, s.streamBufferBytes());
    assertEquals(524288, s.maxJsonResponseBytes());
    assertEquals(4096, s.maxErrorResponseBytes());
    assertEquals(65535, s.maxOutputChars());

    assertFalse(s.gptImage2().paidEnabled());
    assertEquals(900, s.gptImage2().askTimeoutSeconds());
    assertEquals(960000L, s.gptImage2().hubExecutionTimeoutMillis());
    assertEquals(1200000L, s.gptImage2().maxWaitMillis());

    assertFalse(s.seedance().enabled());
    assertNull(s.seedance().workspaceId());
    assertEquals(0, s.seedance().retry());
    assertEquals(600000L, s.seedance().hubExecutionTimeoutMillis());
    assertEquals(30000L, s.seedance().statusPollIntervalMillis());
    assertEquals(1800000L, s.seedance().maxWaitMillis());
  }

  @Test
  void validConfigurationsPass() {
    OpenCliSettings settings =
        new OpenCliSettings(
            true,
            "https://hub.example.com",
            "inst-123",
            10000L,
            60000L,
            150000L,
            32768,
            1048576,
            8192,
            100000,
            new OpenCliSettings.GptImage2(true, 600, 700000L, 1000000L),
            new OpenCliSettings.Seedance(true, "ws-1", 2, 500000L, 20000L, 1000000L));

    assertTrue(settings.enabled());
    assertEquals("https://hub.example.com", settings.baseUrl());
    assertEquals("inst-123", settings.instanceId());
    assertEquals(10000L, settings.connectTimeoutMillis());
    assertEquals("ws-1", settings.seedance().workspaceId());
  }

  @Test
  void baseUrlValidation() {
    assertThatThrownBy(() -> create(true, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl is required when opencli is enabled");

    for (String invalidUrl :
        List.of(
            " https://hub.example.com ",
            "https://hub.example.com/api",
            "https://user:pass@hub.example.com",
            "ftp://hub.example.com",
            "http://invalid host",
            "http://proxy:0",
            "http://proxy:65536",
            "http://proxy:-5")) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> create(false, invalidUrl, null));
      assertNull(error.getCause());
    }

    for (String validUrl :
        List.of(
            "https://hub.example.com/",
            "http://proxy:1",
            "http://127.0.0.1:65535",
            "http://localhost:8080")) {
      assertEquals(validUrl, create(false, validUrl, null).baseUrl());
    }
  }

  @Test
  void instanceIdValidation() {
    assertEquals("instance", create(false, null, " instance ").instanceId());
    assertNull(create(false, null, null).instanceId());
    for (String invalidId : List.of("a".repeat(37), "", " ")) {
      assertThatThrownBy(() -> create(false, null, invalidId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("instanceId must be");
    }
  }

  @Test
  void rootNumericBoundsValidation() {
    assertBound(1L, 120000L, "connectTimeoutMillis", v -> withRootField(v, 0));
    assertBound(1000L, 1800000L, "requestTimeoutMillis", v -> withRootField(v, 1));
    assertBound(121000L, 600000L, "longPollTimeoutMillis", v -> withRootField(v, 2));
    assertBound(1024L, 1048576L, "streamBufferBytes", v -> withRootField(v, 3));
    assertBound(1024L, 4194304L, "maxJsonResponseBytes", v -> withRootField(v, 4));
    assertBound(256L, 65536L, "maxErrorResponseBytes", v -> withRootField(v, 5));
    assertBound(1024L, 1000000L, "maxOutputChars", v -> withRootField(v, 6));
  }

  @Test
  void gptImage2Validation() {
    assertBound(1L, 1740L, "askTimeoutSeconds", a -> gpt(a, 0));
    assertThatThrownBy(() -> gpt(30999L, 1))
        .hasMessage("hubExecutionTimeoutMillis must be between 31000 and 1800000");
    assertThatThrownBy(() -> gpt(1800001L, 1))
        .hasMessage("hubExecutionTimeoutMillis must be between 31000 and 1800000");
    assertThatThrownBy(() -> gpt(31000L, 1)).hasMessageContaining("must exceed askTimeoutSeconds");
    assertDoesNotThrow(() -> gpt(32000L, 1));
    assertDoesNotThrow(() -> gpt(1800000L, 1));
    assertBound(1000L, 7200000L, "maxWaitMillis", w -> gpt(w, 2));

    assertThatThrownBy(() -> new OpenCliSettings.GptImage2(false, 100, 130000L, 1200000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "gptImage2.hubExecutionTimeoutMillis must exceed askTimeoutSeconds plus OpenCLI padding");
    assertDoesNotThrow(() -> new OpenCliSettings.GptImage2(false, 100, 131000L, 1200000L));
  }

  @Test
  void seedanceValidation() {
    assertThatThrownBy(() -> new OpenCliSettings.Seedance(true, null, 0, 600000L, 30000L, 1800000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seedance.workspaceId is required when seedance is enabled");

    for (String invalidWs : List.of("", "a".repeat(257), " ws ")) {
      assertThatThrownBy(
              () -> new OpenCliSettings.Seedance(false, invalidWs, 0, 600000L, 30000L, 1800000L))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertDoesNotThrow(
        () -> new OpenCliSettings.Seedance(false, "a".repeat(256), 0, 600000L, 30000L, 1800000L));

    assertBound(0L, 5L, "retry", r -> seed(r, 0));
    assertBound(1000L, 1800000L, "hubExecutionTimeoutMillis", e -> seed(e, 1));
    assertBound(1L, 300000L, "statusPollIntervalMillis", p -> seed(p, 2));
    assertBound(1000L, 14400000L, "maxWaitMillis", w -> seed(w, 3));

    assertThatThrownBy(() -> new OpenCliSettings.Seedance(false, null, 0, 600000L, 50000L, 50000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seedance.statusPollIntervalMillis must be less than maxWaitMillis");
  }

  @Test
  void parseJsonSuccess() {
    ObjectNode node = defaultJsonNode();
    node.put("enabled", true)
        .put("baseUrl", "https://hub.kkstudio.fun")
        .put("instanceId", "prod-1")
        .put("connectTimeoutMillis", 6000L);
    ((ObjectNode) node.get("gptImage2")).put("paidEnabled", true);
    ((ObjectNode) node.get("seedance")).put("enabled", true).put("workspaceId", "ws-prod");

    OpenCliSettings settings = OpenCliSettings.parse(node.toString());
    assertTrue(settings.enabled());
    assertEquals("https://hub.kkstudio.fun", settings.baseUrl());
    assertEquals("prod-1", settings.instanceId());
    assertEquals(6000L, settings.connectTimeoutMillis());
    assertTrue(settings.gptImage2().paidEnabled());
    assertTrue(settings.seedance().enabled());
    assertEquals("ws-prod", settings.seedance().workspaceId());

    OpenCliSettings defaultResolved = OpenCliSettings.parse(defaultJsonNode().toString());
    assertFalse(defaultResolved.enabled());
    assertNull(defaultResolved.baseUrl());
    assertNull(defaultResolved.instanceId());
    assertNull(defaultResolved.seedance().workspaceId());
  }

  @Test
  void parseJsonFailures() {
    for (String badInput : List.of("", "   ", "null", "{invalid json", "{bad: true} 123")) {
      assertParseMalformed(badInput);
    }
    assertParseMalformed(null);

    ObjectNode leakNode = defaultJsonNode().put("apiKey", "sk-super-secret-key-do-not-leak");
    IllegalArgumentException secretEx =
        assertThrows(
            IllegalArgumentException.class, () -> OpenCliSettings.parse(leakNode.toString()));
    assertEquals(
        "Invalid OpenCLI plugin configuration: malformed configuration payload",
        secretEx.getMessage());
    assertNull(secretEx.getCause());
    assertFalse(secretEx.getMessage().contains("sk-super-secret-key-do-not-leak"));

    ObjectNode nullPrimNode = defaultJsonNode();
    nullPrimNode.putNull("connectTimeoutMillis");
    assertParseMalformed(nullPrimNode.toString());

    assertParseMalformed(defaultJsonNode().put("connectTimeoutMillis", "5000").toString());
    assertParseMalformed(defaultJsonNode().put("enabled", 1).toString());
    assertParseMalformed(defaultJsonNode().put("connectTimeoutMillis", 5000.5).toString());
    assertParseMalformed(defaultJsonNode().put("enabled", true).toString());
  }

  @Test
  void isolatedClassLoaderGptImage2FirstInitializationDoesNotThrow() throws Exception {
    assertIsolatedInit(OpenCliSettings.GptImage2.class);
  }

  @Test
  void isolatedClassLoaderSeedanceFirstInitializationDoesNotThrow() throws Exception {
    assertIsolatedInit(OpenCliSettings.Seedance.class);
  }

  private static void assertDefaultField(Class<?> clazz, ClassLoader loader) throws Exception {
    Class<?> loaded = Class.forName(clazz.getName(), true, loader);
    Field field = loaded.getDeclaredField("DEFAULT");
    field.setAccessible(true);
    assertNotNull(field.get(null));
  }

  private static void assertIsolatedInit(Class<?> target) throws Exception {
    ClassLoader loader = createIsolatedPluginClassLoader();
    assertDefaultField(target, loader);
    assertDefaultField(OpenCliSettings.class, loader);
  }

  private static void assertParseMalformed(String json) {
    assertThatThrownBy(() -> OpenCliSettings.parse(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid OpenCLI plugin configuration: malformed configuration payload")
        .hasNoCause();
  }

  private static OpenCliSettings create(boolean enabled, String url, String inst, long... v) {
    return new OpenCliSettings(
        enabled,
        url,
        inst,
        v.length > 0 ? v[0] : 5000L,
        v.length > 1 ? v[1] : 120000L,
        v.length > 2 ? v[2] : 130000L,
        v.length > 3 ? (int) v[3] : 16384,
        v.length > 4 ? (int) v[4] : 524288,
        v.length > 5 ? (int) v[5] : 4096,
        v.length > 6 ? (int) v[6] : 65535,
        DEFAULT_GPT,
        DEFAULT_SEEDANCE);
  }

  private static OpenCliSettings withRootField(long val, int index) {
    long[] v = new long[] {5000L, 120000L, 130000L, 16384, 524288, 4096, 65535};
    v[index] = val;
    return create(false, null, null, v);
  }

  private static OpenCliSettings.GptImage2 gpt(long v, int idx) {
    return new OpenCliSettings.GptImage2(
        false, idx == 0 ? (int) v : 1, idx == 1 ? v : 1800000L, idx == 2 ? v : 1200000L);
  }

  private static OpenCliSettings.Seedance seed(long v, int idx) {
    return new OpenCliSettings.Seedance(
        false,
        null,
        idx == 0 ? (int) v : 0,
        idx == 1 ? v : 600000L,
        idx == 2 ? v : 1L,
        idx == 3 ? v : 1800000L);
  }

  private static void assertBound(long min, long max, String fieldName, LongFunction<?> factory) {
    assertThatThrownBy(() -> factory.apply(min - 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fieldName + " must be between " + min + " and " + max);
    assertThatThrownBy(() -> factory.apply(max + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fieldName + " must be between " + min + " and " + max);
    assertDoesNotThrow(() -> factory.apply(min));
    assertDoesNotThrow(() -> factory.apply(max));
  }

  private static ObjectNode defaultJsonNode() {
    try {
      String defaults = OpenCliPlugin.CONFIGURATION_SCHEMA.resolveValues("{}");
      return (ObjectNode) MAPPER.readTree(defaults);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ClassLoader createIsolatedPluginClassLoader() {
    ClassLoader parent = OpenCliSettingsTest.class.getClassLoader();
    String prefix = OpenCliSettings.class.getName();
    return new ClassLoader(parent) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(prefix)) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded != null) {
            return loaded;
          }
          String resourcePath = name.replace('.', '/') + ".class";
          try (InputStream in = parent.getResourceAsStream(resourcePath)) {
            if (in == null) {
              throw new ClassNotFoundException(name);
            }
            byte[] bytes = in.readAllBytes();
            Class<?> c = defineClass(name, bytes, 0, bytes.length);
            if (resolve) {
              resolveClass(c);
            }
            return c;
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        return super.loadClass(name, resolve);
      }
    };
  }
}
