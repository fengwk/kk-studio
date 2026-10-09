package fun.fengwk.kkstudio.share.ai.environment;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

class DaemonConfigurationCodecTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path temp;

  @Test
  void normalizesWithoutMutatingInputAndPreservesOrder() throws Exception {
    ObjectNode json = MAPPER.createObjectNode().put("studioUrl", "https://studio.example/");
    json.put("note", "\u2003 trusted note \u2003").put("bashExecutable", "/opt/custom bash");
    ObjectNode servers = json.putObject("lsp").putObject("servers");
    for (String id : List.of("z", "a")) {
      ObjectNode server = servers.putObject(id);
      server.putArray("command").add("~/bin/server").add(" argument with spaces ");
      server.putArray("extensions").add(".JAVA");
      server.putArray("rootMarkers").add("build/pom.xml");
      server.putArray("firstMatchMarkers").add(".git");
    }
    DaemonConfiguration parsed = DaemonConfigurationCodec.parse(json);
    assertEquals("https://studio.example", parsed.getStudioUrl());
    assertEquals("trusted note", parsed.getNote());
    assertEquals(List.of("z", "a"), List.copyOf(parsed.getLsp().getServers().keySet()));
    assertEquals(List.of(".java"), parsed.getLsp().getServers().get("z").getExtensions());
    assertEquals(
        " argument with spaces ", parsed.getLsp().getServers().get("z").getCommand().get(1));
    assertEquals("\u2003 trusted note \u2003", json.get("note").textValue());
    assertEquals(
        URI.create("wss://studio.example/api/harness/environment-daemon/v1"),
        DaemonConfigurationCodec.gatewayUri(parsed.getStudioUrl()));
    assertEquals(parsed, DaemonConfigurationCodec.validate(parsed));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"http://localhost", "https://host:65535/", "http://[::1]:8080", "HTTPS://host/"})
  void acceptsOrigins(String origin) {
    assertNotNull(DaemonConfigurationCodec.gatewayUri(origin));
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
    assertSafe(() -> DaemonConfigurationCodec.gatewayUri(origin), "daemon.studioUrl");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "[]",
        "true",
        "{}",
        "{\"studioUrl\":1}",
        "{\"studioUrl\":\"https://host\",\"note\":false}",
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
        assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.parse(node));
    assertTrue(error.getMessage().startsWith("daemon"));
    assertNull(error.getCause());
  }

  @Test
  void optionalNullsAndOmittedMarkersAreAccepted() throws Exception {
    DaemonConfiguration config =
        DaemonConfigurationCodec.parse(
            MAPPER.readTree(
                "{\"studioUrl\":\"http://host\",\"note\":null,\"bashExecutable\":null,\"lsp\":null}"));
    assertNull(config.getNote());
    assertNull(config.getLsp());
    var lsp =
        DaemonConfigurationCodec.parseLsp(
            MAPPER.readTree(
                "{\"servers\":{\"java\":{\"command\":[\"jdtls\"],\"extensions\":[\".java\"]}}}"));
    assertEquals(List.of(), lsp.getServers().get("java").getRootMarkers());
    assertEquals(List.of(), lsp.getServers().get("java").getFirstMatchMarkers());
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.validate(null));
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
    DaemonConfiguration config = basic();
    config.setNote(text);
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.note");
    config.setNote(null);
    config.setBashExecutable(text);
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.bashExecutable");
    config.setBashExecutable(null);
    config.setLsp(lsp());
    config.getLsp().getServers().get("java").setCommand(List.of(text));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "command[0]");
  }

  @Test
  void noteLengthBoundary() {
    DaemonConfiguration config = basic();
    config.setNote("x".repeat(512));
    assertEquals(512, DaemonConfigurationCodec.validate(config).getNote().length());
    config.setNote("x".repeat(513));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.note");
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
    DaemonConfiguration config = basic();
    config.setLsp(lsp());
    var server = config.getLsp().getServers().get("java");
    server.setRootMarkers(List.of(marker));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "rootMarkers[0]");
    server.setRootMarkers(null);
    server.setFirstMatchMarkers(List.of(marker));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "firstMatchMarkers[0]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"java", ".", ".dir/SECRET", ".dir\\SECRET", ".SECRET\n"})
  void rejectsUnsafeExtensions(String extension) {
    DaemonConfiguration config = basic();
    config.setLsp(lsp());
    config.getLsp().getServers().get("java").setExtensions(List.of(extension));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "extensions[0]");
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
        "{\"servers\":{\"java\":{\"command\":[],\"extensions\":[\".java\"]}}}",
        "{\"servers\":{\"java\":{\"command\":[\"jdtls\"],\"extensions\":[]}}}",
        "{\"servers\":{\"java\":{\"command\":[\"jdtls\"],\"extensions\":[\".java\"],\"requestTimeoutMs\":1}}}"
      })
  void rejectsInvalidServers(String json) throws Exception {
    var node = MAPPER.readTree(json);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.parseLsp(node));
  }

  @Test
  void directModelValidationRejectsMissingServersAndEntries() {
    DaemonConfiguration config = basic();
    config.setLsp(new DaemonLspConfiguration());
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.validate(config));
    config.getLsp().setServers(new LinkedHashMap<>());
    config.getLsp().getServers().put("java", null);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.validate(config));
    config.getLsp().getServers().clear();
    config.getLsp().getServers().put("bad\nSECRET", new DaemonLspServerConfiguration());
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.lsp.servers");
  }

  @Test
  void fileReaderRejectsDuplicateKeysAndTrailingTokensWithoutLeak() throws Exception {
    Path file = temp.resolve("daemon.json");
    for (String json :
        List.of(
            "{\"studioUrl\":\"http://host\",\"studioUrl\":\"SECRET\"}",
            "{\"studioUrl\":\"http://host\",\"lsp\":{\"servers\":{\"java\":{},\"java\":{\"SECRET\":true}}}}",
            "{\"studioUrl\":\"http://host\"} {\"SECRET\":true}",
            "{\"studioUrl\":\"SECRET")) {
      Files.writeString(file, json);
      assertSafe(() -> DaemonConfigurationCodec.read(file), "daemon");
    }
    Files.writeString(file, "{\"studioUrl\":\"http://host\"}");
    assertEquals("http://host", DaemonConfigurationCodec.read(file).getStudioUrl());
    Files.delete(file);
    assertSafe(() -> DaemonConfigurationCodec.read(file), "daemon");
  }

  @Test
  void normalizesTerminalAndPreservesArgvExactly() throws Exception {
    DaemonConfiguration parsed =
        DaemonConfigurationCodec.parse(
            MAPPER.readTree(
                "{\"studioUrl\":\"http://host\",\"terminal\":"
                    + "{\"executable\":\" /bin/zsh \",\"args\":[\"-l\",\"\",\"  x  \"],"
                    + "\"workdir\":\" /opt \"}}"));
    DaemonTerminalConfiguration terminal = parsed.getTerminal();
    assertEquals(" /bin/zsh ", terminal.getExecutable());
    assertEquals(List.of("-l", "", "  x  "), terminal.getArgs());
    assertEquals(" /opt ", terminal.getWorkdir());
    assertThrows(UnsupportedOperationException.class, () -> terminal.getArgs().add("mutate"));
    assertEquals(parsed, DaemonConfigurationCodec.validate(parsed));

    assertNull(
        DaemonConfigurationCodec.parse(MAPPER.readTree("{\"studioUrl\":\"http://host\"}"))
            .getTerminal());
    assertNull(
        DaemonConfigurationCodec.parse(
                MAPPER.readTree("{\"studioUrl\":\"http://host\",\"terminal\":null}"))
            .getTerminal());
    DaemonTerminalConfiguration empty =
        DaemonConfigurationCodec.parse(
                MAPPER.readTree("{\"studioUrl\":\"http://host\",\"terminal\":{}}"))
            .getTerminal();
    assertNull(empty);
    assertEquals(
        DaemonConfigurationCodec.parse(MAPPER.readTree("{\"studioUrl\":\"http://host\"}")),
        DaemonConfigurationCodec.parse(
            MAPPER.readTree("{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[]}}")));
    DaemonConfiguration withEmptyArgument =
        DaemonConfigurationCodec.parse(
            MAPPER.readTree("{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[\"\"]}}"));
    assertEquals(List.of(""), withEmptyArgument.getTerminal().getArgs());
    DaemonConfiguration secret =
        DaemonConfigurationCodec.parse(
            MAPPER.readTree(
                "{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[\"SECRET\"]}}"));
    assertFalse(secret.toString().contains("SECRET"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"studioUrl\":\"http://host\",\"terminal\":[]}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"unknown\":1}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"executable\":1}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"executable\":\"  \"}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":\"x\"}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[1]}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[null]}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"args\":[\"SECRET\\u0000\"]}}",
        "{\"studioUrl\":\"http://host\",\"terminal\":{\"workdir\":\"SECRET\\n\"}}"
      })
  void rejectsInvalidTerminalStructure(String json) throws Exception {
    var node = MAPPER.readTree(json);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> DaemonConfigurationCodec.parse(node));
    assertTrue(error.getMessage().startsWith("daemon.terminal"), error.getMessage());
    assertFalse(error.getMessage().contains("SECRET"));
    assertNull(error.getCause());
  }

  @Test
  void terminalRejectsControlCharactersAndNullArgvEntries() {
    DaemonConfiguration config = basic();
    DaemonTerminalConfiguration terminal = new DaemonTerminalConfiguration();
    config.setTerminal(terminal);
    terminal.setExecutable("SECRET\u0000");
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.terminal.executable");
    terminal.setExecutable(null);
    terminal.setWorkdir("SECRET\u2028");
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.terminal.workdir");
    terminal.setWorkdir(null);
    terminal.setArgs(Arrays.asList("ok", null));
    assertSafe(() -> DaemonConfigurationCodec.validate(config), "daemon.terminal.args");
  }

  private static DaemonConfiguration basic() {
    DaemonConfiguration config = new DaemonConfiguration();
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
