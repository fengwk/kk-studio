package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional local LSP command bridge plus deterministic unavailable errors and a {@code javap}
 * fallback for resolvable Java class targets.
 *
 * <p>This is intentionally not a full LSP client. When no bridge command is configured, goto and
 * workspace-symbol queries fail with an explicit unavailable message. {@code java_decompile} may
 * still succeed via {@code javap} for resolvable class names or class files.
 */
final class LspBridge {

  static final String UNAVAILABLE_MESSAGE =
      "LSP bridge is unavailable. Configure kkstudio.daemon.lsp-bridge to enable real LSP queries.";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern JDT_CLASS =
      Pattern.compile("jdt://contents/[^/]+/(.+?)\\.class(?:\\?.*)?$");
  private static final Pattern CLASS_TOKEN =
      Pattern.compile("([A-Za-z_][\\w.$]*(?:/[\\w.$]+)*\\.class)");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final String bridgeCommand;
  private final String javapExecutable;

  LspBridge(CodingToolsConfig config) {
    this.bridgeCommand = config.lspBridgeCommand();
    this.javapExecutable = config.javapExecutable();
  }

  LspBridge(String bridgeCommand, String javapExecutable) {
    this.bridgeCommand = blankToNull(bridgeCommand);
    this.javapExecutable =
        Objects.requireNonNull(
            blankToNull(javapExecutable) == null
                ? CodingToolsConfig.DEFAULT_JAVAP_EXECUTABLE
                : javapExecutable,
            "javapExecutable");
  }

  boolean bridgeAvailable() {
    return bridgeCommand != null;
  }

  String gotoDefinition(Path path, int line, int character) throws Exception {
    return invokeBridge(
        request("goto_definition")
            .put("path", path.toString())
            .put("line", line)
            .put("character", character));
  }

  String workspaceSymbols(Path path, String query, int limit) throws Exception {
    return invokeBridge(
        request("workspace_symbols")
            .put("path", path.toString())
            .put("query", query)
            .put("limit", limit));
  }

  String javaDecompile(Path path, String target) throws Exception {
    if (bridgeAvailable()) {
      try {
        return invokeBridge(
            request("java_decompile").put("path", path.toString()).put("target", target));
      } catch (IllegalStateException | IOException bridgeError) {
        String fallback = tryJavap(path, target);
        if (fallback != null) {
          return fallback
              + "\n\n(note: LSP bridge failed; used javap fallback: "
              + bridgeError.getMessage()
              + ")";
        }
        throw bridgeError;
      }
    }
    String fallback = tryJavap(path, target);
    if (fallback != null) {
      return fallback + "\n\n(note: LSP bridge unavailable; used javap fallback)";
    }
    throw new IllegalStateException(
        UNAVAILABLE_MESSAGE
            + " javap fallback could not resolve target: "
            + summarizeTarget(target));
  }

  String tryJavap(Path workspacePath, String target) throws Exception {
    ResolvedClass resolved = resolveClassTarget(target, workspacePath);
    if (resolved == null) {
      return null;
    }
    List<String> command = new ArrayList<>();
    command.add(javapExecutable);
    command.add("-c");
    command.add("-p");
    if (resolved.classpath() != null) {
      command.add("-classpath");
      command.add(resolved.classpath());
    }
    command.add(resolved.className());
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = readFully(process.getInputStream());
    boolean finished = process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("javap timed out for " + resolved.className());
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          "javap failed for "
              + resolved.className()
              + " (exit "
              + process.exitValue()
              + "): "
              + output.trim());
    }
    return output;
  }

  private ObjectNode request(String op) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("op", op);
    return node;
  }

  private String invokeBridge(ObjectNode request) throws Exception {
    if (!bridgeAvailable()) {
      throw new IllegalStateException(UNAVAILABLE_MESSAGE);
    }
    List<String> command = shellCommand(bridgeCommand);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    process.getOutputStream().write(MAPPER.writeValueAsBytes(request));
    process.getOutputStream().close();
    String raw = readFully(process.getInputStream());
    boolean finished = process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("LSP bridge timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          "LSP bridge exited with code " + process.exitValue() + ": " + raw.trim());
    }
    JsonNode response = MAPPER.readTree(raw.isBlank() ? "{}" : raw);
    if (response.path("ok").asBoolean(false)) {
      JsonNode text = response.get("text");
      if (text == null || !text.isTextual()) {
        throw new IllegalStateException("LSP bridge response missing textual 'text' field");
      }
      return text.textValue();
    }
    String error = response.path("error").asText(raw.trim());
    throw new IllegalStateException(error.isBlank() ? "LSP bridge returned an error" : error);
  }

  static ResolvedClass resolveClassTarget(String target, Path workspacePath) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String value = target.trim();
    Matcher jdt = JDT_CLASS.matcher(value);
    if (jdt.find()) {
      return new ResolvedClass(jdt.group(1).replace('/', '.'), null);
    }
    int jdtIndex = value.indexOf("jdt://");
    if (jdtIndex >= 0) {
      Matcher nested = JDT_CLASS.matcher(value.substring(jdtIndex));
      if (nested.find()) {
        return new ResolvedClass(nested.group(1).replace('/', '.'), null);
      }
    }
    if (value.endsWith(".class") && !value.contains("://")) {
      Path classFile = Path.of(value);
      if (!classFile.isAbsolute() && workspacePath != null) {
        Path parent = workspacePath.getParent();
        if (parent != null) {
          classFile = parent.resolve(value).normalize();
        }
      }
      if (Files.isRegularFile(classFile)) {
        Path fileName = classFile.getFileName();
        if (fileName == null) {
          return null;
        }
        String fileNameText = fileName.toString();
        String simple = fileNameText.substring(0, fileNameText.length() - ".class".length());
        Path parent = classFile.getParent();
        return new ResolvedClass(simple, parent == null ? "." : parent.toString());
      }
    }
    Matcher classToken = CLASS_TOKEN.matcher(value);
    if (classToken.find()) {
      String token = classToken.group(1);
      String binary = token.substring(0, token.length() - ".class".length()).replace('/', '.');
      return new ResolvedClass(binary, null);
    }
    if (value.matches("[A-Za-z_][\\w.$]*")) {
      return new ResolvedClass(value, null);
    }
    // "String (Class) - ..." style without jdt uri
    int paren = value.indexOf(" (");
    if (paren > 0) {
      String candidate = value.substring(0, paren).trim();
      if (candidate.matches("[A-Za-z_][\\w.$]*")) {
        // bare simple names without package are still attempted via javap bootstrap path
        String fqn = candidate.contains(".") ? candidate : "java.lang." + candidate;
        return new ResolvedClass(fqn, null);
      }
    }
    return null;
  }

  private static List<String> shellCommand(String command) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      return List.of("cmd.exe", "/c", command);
    }
    return List.of("sh", "-c", command);
  }

  private static String readFully(InputStream input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    input.transferTo(buffer);
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static String summarizeTarget(String target) {
    if (target == null) {
      return "<missing>";
    }
    return target.length() <= 120 ? target : target.substring(0, 117) + "...";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  record ResolvedClass(String className, String classpath) {}
}
