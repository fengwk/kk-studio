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

import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.DaemonConfiguration;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.EnvironmentConfig;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.environment.server.DaemonConfigProtocolMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

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
                "{\"studioUrl\":\"http://host\",\"lsp\":{\"servers\":{\"java\":{\"command\":[\"jdtls\"],\"extensions\":[\" .JAVA \"]}}}}"));
    var lsp = configWithLsp.getLsp();
    assertEquals(List.of(".java"), lsp.getServers().get("java").getExtensions());
    assertEquals(List.of(), lsp.getServers().get("java").getRootMarkers());
    assertEquals(List.of(), lsp.getServers().get("java").getFirstMatchMarkers());
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigCodec.validate(null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"command\":[\"jdtls\"]}",
        "{\"command\":[\"jdtls\"],\"extensions\":null}",
        "{\"command\":[\"jdtls\"],\"extensions\":[]}"
      })
  void rejectsMissingEmptyOrNullExtensions(String server) throws Exception {
    var node =
        MAPPER.readTree(
            "{\"studioUrl\":\"http://host\",\"lsp\":{\"servers\":{\"java\":" + server + "}}}");
    assertSafe(() -> DaemonConfigCodec.parse(node), "daemonConfig.lsp.servers.java.extensions");
  }

  @Test
  void rejectsDirectlyConstructedEmptyExtensions() {
    DaemonConfigDTO omitted = basic();
    omitted.setLsp(lsp());
    omitted.getLsp().getServers().get("java").setExtensions(null);
    assertSafe(
        () -> DaemonConfigCodec.validate(omitted), "daemonConfig.lsp.servers.java.extensions");
    DaemonConfigDTO empty = basic();
    empty.setLsp(lsp());
    empty.getLsp().getServers().get("java").setExtensions(List.of());
    assertSafe(() -> DaemonConfigCodec.validate(empty), "daemonConfig.lsp.servers.java.extensions");
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

  @ParameterizedTest
  @ValueSource(strings = {" /bin/bash", "/bin/bash ", " /bin/bash ", "\t/bin/bash"})
  void rejectsBashExecutableWithSurroundingWhitespace(String text) {
    DaemonConfigDTO config = basic();
    config.setBashExecutable(text);
    assertSafe(() -> DaemonConfigCodec.validate(config), "daemonConfig.bashExecutable");
  }

  /** 不成对的代理项能通过 JSON 读入但无法进入 UTF-8 wire：必须在保存阶段就拒绝，而不是下发时失败。 */
  @Test
  void rejectsUnpairedSurrogatesThatCannotBeDelivered() {
    DaemonConfigDTO bash = basic();
    bash.setBashExecutable("/bin/\ud800bash");
    assertSafe(() -> DaemonConfigCodec.validate(bash), "daemonConfig.bashExecutable");

    DaemonConfigDTO command = basic();
    command.setLsp(lsp());
    command.getLsp().getServers().get("java").setCommand(List.of("jdtls\udc00"));
    assertSafe(() -> DaemonConfigCodec.validate(command), "command[0]");
  }

  @Test
  void boundsBashExecutableByTheProtocolLimit() {
    DaemonConfigDTO longest = basic();
    longest.setBashExecutable("/" + "a".repeat(4095));
    assertEquals(4096, DaemonConfigCodec.validate(longest).getBashExecutable().length());

    DaemonConfigDTO tooLong = basic();
    tooLong.setBashExecutable("/" + "a".repeat(4096));
    assertSafe(() -> DaemonConfigCodec.validate(tooLong), "daemonConfig.bashExecutable");
  }

  @Test
  void boundsLspEntriesByTheProtocolLimits() {
    DaemonConfigDTO command = basic();
    command.setLsp(lsp());
    command.getLsp().getServers().get("java").setCommand(List.of("j".repeat(4097)));
    assertSafe(() -> DaemonConfigCodec.validate(command), "command[0]");

    DaemonConfigDTO marker = basic();
    marker.setLsp(lsp());
    marker.getLsp().getServers().get("java").setRootMarkers(List.of("m".repeat(4097)));
    assertSafe(() -> DaemonConfigCodec.validate(marker), "rootMarkers[0]");

    DaemonConfigDTO firstMatch = basic();
    firstMatch.setLsp(lsp());
    firstMatch.getLsp().getServers().get("java").setFirstMatchMarkers(List.of("m".repeat(4097)));
    assertSafe(() -> DaemonConfigCodec.validate(firstMatch), "firstMatchMarkers[0]");

    DaemonConfigDTO longest = basic();
    longest.setLsp(lsp());
    longest.getLsp().getServers().get("java").setRootMarkers(List.of("m".repeat(4096)));
    assertEquals(
        List.of("m".repeat(4096)),
        DaemonConfigCodec.validate(longest).getLsp().getServers().get("java").getRootMarkers());

    DaemonConfigDTO longId = basic();
    longId.setLsp(lsp());
    longId.getLsp().getServers().put("a".repeat(257), lsp().getServers().get("java"));
    assertSafe(() -> DaemonConfigCodec.validate(longId), "daemonConfig.lsp.servers");
  }

  /**
   * 卡片校验与出站协议校验必须同样严格：保存成功的配置一定能下发，否则环境会停留在 CONNECTING。
   *
   * <p>每个样本同时跑两处校验，只允许“两侧都接受”或“两侧都拒绝”。
   */
  @Test
  void cardValidationIsAsStrictAsOutboundProtocolValidation() {
    assertSameAcceptance(config -> config.setBashExecutable("/bin/bash"));
    assertSameAcceptance(config -> config.setBashExecutable(" /bin/bash "));
    assertSameAcceptance(config -> config.setBashExecutable("/" + "a".repeat(4095)));
    assertSameAcceptance(config -> config.setBashExecutable("/" + "a".repeat(4096)));
    assertSameAcceptance(
        config -> config.getLsp().getServers().get("java").setCommand(List.of("j".repeat(4096))));
    assertSameAcceptance(
        config -> config.getLsp().getServers().get("java").setCommand(List.of("j".repeat(4097))));
    assertSameAcceptance(
        config ->
            config
                .getLsp()
                .getServers()
                .get("java")
                .setFirstMatchMarkers(List.of("m".repeat(4096))));
    assertSameAcceptance(
        config ->
            config
                .getLsp()
                .getServers()
                .get("java")
                .setFirstMatchMarkers(List.of("m".repeat(4097))));
    assertSameAcceptance(
        config ->
            config
                .getLsp()
                .getServers()
                .put("a".repeat(256), config.getLsp().getServers().get("java")));
    assertSameAcceptance(
        config ->
            config
                .getLsp()
                .getServers()
                .put("a".repeat(257), config.getLsp().getServers().get("java")));
    assertSameAcceptance(config -> config.setBashExecutable("/bin/\ud800bash"));
    assertSameAcceptance(config -> config.setProxyUrl("http://host:" + "1".repeat(4) + "0"));
    assertSameAcceptance(config -> config.setStudioUrl("https://" + "a".repeat(2040)));
    assertSameAcceptance(config -> config.setStudioUrl("https://" + "a".repeat(2041)));
    assertSameAcceptance(config -> config.setProxyUrl("http://" + "a".repeat(2040) + ":8080"));
    assertSameAcceptance(config -> config.setProxyUrl("http://" + "a".repeat(2041) + ":8080"));
  }

  private static void assertSameAcceptance(Consumer<DaemonConfigDTO> mutation) {
    DaemonConfigDTO config = basic();
    config.setLsp(lsp());
    mutation.accept(config);
    DaemonConfigDTO validated;
    try {
      validated = DaemonConfigCodec.validate(config);
    } catch (IllegalArgumentException error) {
      return;
    }
    DaemonConfiguration protocol = DaemonConfigProtocolMapper.toProtocol(validated);
    Envelope update =
        Envelope.newBuilder()
            .setMessageType(MessageType.ENVIRONMENT_CONFIG)
            .setEnvironmentId("11111111-1111-4111-8111-111111111111")
            .setEnvironmentConfig(
                EnvironmentConfig.newBuilder().setVersion(1L).setConfig(protocol).build())
            .build();
    try {
      ControlProtocolCodec.encodeFrame(update);
    } catch (ControlProtocolException error) {
      throw new AssertionError(
          "a card that passes validation must be deliverable: " + error.getMessage(), error);
    }
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
