package fun.fengwk.kkstudio.platform.environment.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;

class DaemonConfigCodecTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void normalizesWithoutMutatingInputAndPreservesOrder() throws Exception {
    ObjectNode json = MAPPER.createObjectNode().put("studioUrl", "https://studio.example/");
    json.put("bashExecutable", "/opt/custom bash").put("proxyUrl", "http://127.0.0.1:7890");
    ObjectNode servers = json.putObject("lsp").putObject("servers");
    for (String id : List.of("z", "a")) {
      ObjectNode server = servers.putObject(id);
      server.putArray("command").add("~/bin/server").add(" argument with spaces ");
      server.putArray("extensions").add(".JAVA");
      server.putArray("rootMarkers").add("build/pom.xml");
      server.putArray("firstMatchMarkers").add(".git");
    }
    DaemonConfigDTO parsed = DaemonConfigCodec.parse(json);
    assertEquals("https://studio.example", parsed.getStudioUrl());
    assertEquals("http://127.0.0.1:7890", parsed.getProxyUrl());
    assertEquals(List.of("z", "a"), List.copyOf(parsed.getLsp().getServers().keySet()));
    assertEquals(List.of(".java"), parsed.getLsp().getServers().get("z").getExtensions());
    assertEquals(
        " argument with spaces ", parsed.getLsp().getServers().get("z").getCommand().get(1));
    assertEquals("https://studio.example/", json.get("studioUrl").stringValue());
    assertEquals(parsed, DaemonConfigCodec.validate(parsed));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"http://localhost", "https://host:65535/", "http://[::1]:8080", "HTTPS://host/"})
  void acceptsOrigins(String origin) {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl(origin);
    assertNotNull(DaemonConfigCodec.validate(config).getStudioUrl());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "ws://host",
        "https://user:SECRET@host",
        "https://host/SECRET",
        "https://host?SECRET",
        "https://host#SECRET",
        "https://host:",
        "https://host:0",
        "https://host:65536",
        "https://host:-1",
        "https://host:abc",
        "https://host:999999999999",
        "https:///host",
        "https://bad_host",
        "https://[not-ip]",
        "https://host\n",
        "https://host\u007f"
      })
  void rejectsInvalidOriginsWithoutValues(String origin) {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl(origin);
    assertSafe(() -> DaemonConfigCodec.validate(config), "daemonConfig.studioUrl");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"http://127.0.0.1:7890", "HTTP://proxy.internal:3128/", "http://[::1]:8080"})
  void acceptsUnauthenticatedHttpProxy(String proxyUrl) {
    DaemonConfigDTO config = basic();
    config.setProxyUrl(proxyUrl);
    assertNotNull(DaemonConfigCodec.validate(config).getProxyUrl());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " ",
        "https://proxy:8080",
        "http://user:SECRET@proxy:8080",
        "http://proxy:8080/path",
        "http://proxy:8080?SECRET",
        "http://proxy:8080#SECRET",
        "http://proxy",
        "http://proxy:",
        "http://proxy:0",
        "http://proxy:65536",
        "socks://proxy:1080"
      })
  void normalizesBlankProxyToNullAndRejectsOtherShapes(String proxyUrl) {
    DaemonConfigDTO config = basic();
    config.setProxyUrl(proxyUrl);
    if (proxyUrl.isBlank()) {
      assertNull(DaemonConfigCodec.validate(config).getProxyUrl());
      return;
    }
    assertSafe(() -> DaemonConfigCodec.validate(config), "daemonConfig.proxyUrl");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "true",
        "{}",
        "{\"studioUrl\":1}",
        "{\"studioUrl\":\"https://host\",\"note\":\"removed\"}",
        "{\"studioUrl\":\"https://host\",\"bashExecutable\":[]}",
        "{\"studioUrl\":\"https://host\",\"SECRET\":\"SECRET\"}",
        "{\"studioUrl\":\"https://host\",\"lsp\":[]}",
        "{\"studioUrl\":\"https://host\",\"lsp\":{}}",
        "{\"studioUrl\":\"https://host\",\"lsp\":{\"servers\":{}}}",
        "{\"studioUrl\":\"https://host\",\"lsp\":{\"servers\":[]}}"
      })
  void rejectsWrongStructure(String json) throws Exception {
    var node = MAPPER.readTree(json);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.parse(node));
    assertTrue(error.getMessage().startsWith("daemonConfig"));
    assertNull(error.getCause());
  }

  @Test
  void optionalNullsAndOmittedMarkersAreAccepted() throws Exception {
    DaemonConfigDTO config =
        DaemonConfigCodec.parse(
            MAPPER.readTree(
                "{\"studioUrl\":\"http://host\",\"bashExecutable\":null,\"lsp\":null,\"proxyUrl\":null}"));
    assertNull(config.getProxyUrl());
    assertNull(config.getLsp());
    DaemonConfigDTO configWithLsp =
        DaemonConfigCodec.parse(
            MAPPER.readTree(
                "{\"studioUrl\":\"http://host\",\"lsp\":{\"servers\":{\"java\":{\"command\":[\"jdtls\"]}}}}"));
    var lsp = configWithLsp.getLsp();
    assertEquals(List.of(), lsp.getServers().get("java").getExtensions());
    assertEquals(List.of(), lsp.getServers().get("java").getRootMarkers());
    assertEquals(List.of(), lsp.getServers().get("java").getFirstMatchMarkers());
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.validate(null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "  ",
        "SECRET\n",
        "SECRET\r",
        "SECRET\t",
        "SECRET\u0000",
        "SECRET\u0085",
        "SECRET\u2028",
        "SECRET\u2029"
      })
  void rejectsBlankOrControlledText(String text) {
    DaemonConfigDTO config = basic();
    config.setBashExecutable(text);
    assertSafe(() -> DaemonConfigCodec.validate(config), "daemonConfig.bashExecutable");
    config.setBashExecutable(null);
    config.setLsp(lsp());
    config.getLsp().getServers().get("java").setCommand(List.of(text));
    assertSafe(() -> DaemonConfigCodec.validate(config), "command[0]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "..",
        ".",
        "../SECRET",
        "dir/../SECRET",
        "dir\\..\\SECRET",
        "/SECRET",
        "\\SECRET",
        "C:\\SECRET",
        "dir//SECRET",
        "SECRET\n"
      })
  void rejectsUnsafeMarkers(String marker) {
    DaemonConfigDTO config = basic();
    config.setLsp(lsp());
    var server = config.getLsp().getServers().get("java");
    server.setRootMarkers(List.of(marker));
    assertSafe(() -> DaemonConfigCodec.validate(config), "rootMarkers[0]");
    server.setRootMarkers(null);
    server.setFirstMatchMarkers(List.of(marker));
    assertSafe(() -> DaemonConfigCodec.validate(config), "firstMatchMarkers[0]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"java", ".", ".dir/SECRET", ".dir\\SECRET", ".SECRET\n"})
  void rejectsUnsafeExtensions(String extension) {
    DaemonConfigDTO config = basic();
    config.setLsp(lsp());
    config.getLsp().getServers().get("java").setExtensions(List.of(extension));
    assertSafe(() -> DaemonConfigCodec.validate(config), "extensions[0]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"servers\":{\"bad/SECRET\":{}}}",
        "{\"servers\":{\"java\":null}}",
        "{\"SECRET\":true,\"servers\":{}}",
        "{\"servers\":{\"java\":{\"command\":\"SECRET\"}}}",
        "{\"servers\":{\"java\":{\"command\":[1]}}}",
        "{\"servers\":{\"java\":{\"command\":[null]}}}",
        "{\"servers\":{\"java\":{\"command\":[]}}}",
        "{\"servers\":{\"java\":{\"command\":[\"jdtls\"],\"extensions\":[\".java\"],\"requestTimeoutMs\":1}}}"
      })
  void rejectsInvalidServers(String json) throws Exception {
    var node = MAPPER.readTree("{\"studioUrl\":\"http://host\",\"lsp\":" + json + "}");
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.parse(node));
  }

  @Test
  void directModelValidationRejectsMissingServersAndEntries() {
    DaemonConfigDTO config = basic();
    config.setLsp(new DaemonLspConfiguration());
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.validate(config));
    config.getLsp().setServers(new LinkedHashMap<>());
    config.getLsp().getServers().put("java", null);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.validate(config));
    config.getLsp().getServers().clear();
    config.getLsp().getServers().put("bad\nSECRET", new DaemonLspServerConfiguration());
    assertSafe(() -> DaemonConfigCodec.validate(config), "daemonConfig.lsp.servers");
  }

  @Test
  void strictReaderRejectsDuplicateKeysAndTrailingTokensWithoutLeak() {
    for (String json :
        List.of(
            "{\"studioUrl\":\"http://host\",\"studioUrl\":\"SECRET\"}",
            "{\"studioUrl\":\"http://host\",\"lsp\":{\"servers\":{\"java\":{},\"java\":{\"SECRET\":true}}}}",
            "{\"studioUrl\":\"http://host\"} {\"SECRET\":true}",
            "{\"studioUrl\":\"SECRET")) {
      assertSafe(() -> DaemonConfigCodec.read(json), "daemonConfig");
    }
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.read(null));
  }

  @Test
  void canonicalWriterEmitsExplicitNullOptionalValues() throws Exception {
    DaemonConfigDTO config = basic();
    String json = DaemonConfigCodec.write(config);
    JsonNode node = MAPPER.readTree(json);
    assertTrue(node.has("proxyUrl"));
    assertTrue(node.get("proxyUrl").isNull());
    assertTrue(node.has("bashExecutable"));
    assertTrue(node.get("bashExecutable").isNull());
    assertTrue(node.has("lsp"));
    assertTrue(node.get("lsp").isNull());
    assertEquals("https://host", DaemonConfigCodec.read(json).getStudioUrl());
    assertFalse(json.contains("note"));
  }

  private static DaemonConfigDTO basic() {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl("https://host");
    return config;
  }

  private static DaemonLspConfiguration lsp() {
    DaemonLspServerConfiguration server = new DaemonLspServerConfiguration();
    server.setCommand(List.of("jdtls"));
    server.setExtensions(List.of(".java"));
    DaemonLspConfiguration lsp = new DaemonLspConfiguration();
    lsp.setServers(new LinkedHashMap<>());
    lsp.getServers().put("java", server);
    return lsp;
  }

  private static void assertSafe(Runnable action, String field) {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
    assertTrue(error.getMessage().contains(field), error.getMessage());
    assertFalse(error.getMessage().contains("SECRET"));
    assertNull(error.getCause());
  }
}
