package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可选的本机 LSP 命令 bridge，附带确定性的不可用错误，以及对可解析 Java class 目标的 {@code javap} 回退。
 *
 * <p>这里有意不做成完整的 LSP client。未配置 bridge 命令时，goto 与 workspace-symbol 查询会以明确的不可用消息失败；{@code
 * java_decompile} 对可解析的 class 名或 class 文件仍可通过 {@code javap} 成功。
 *
 * <p>bridge 与 {@code javap} 子进程都在本次 capability arguments 显式指定的 workdir 中启动，不继承 Daemon 进程目录；两者共享
 * {@link ChildProcessRunner} 的并发排空、调用方有效超时与进程树终止语义。
 */
final class LspBridge {

  static final String UNAVAILABLE_MESSAGE =
      "LSP bridge is unavailable. Pass --lsp-bridge-command to enable real LSP queries.";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern JDT_CLASS =
      Pattern.compile("jdt://contents/[^/]+/(.+?)\\.class(?:\\?.*)?$");
  private static final Pattern CLASS_TOKEN =
      Pattern.compile("([A-Za-z_][\\w.$]*(?:/[\\w.$]+)*\\.class)");

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

  /**
   * 解析符号定义。
   *
   * @param cancellation 调用方取消信号；deadline 由 {@code timeout} 表达
   * @param timeout 本次调用的有效超时
   */
  String gotoDefinition(
      Path workdir,
      Path path,
      int line,
      int character,
      Duration timeout,
      ChildProcessRunner.CancellationCheck cancellation)
      throws Exception {
    String output =
        invokeBridge(
            workdir,
            request("goto_definition")
                .put("workdir", workdir.toString())
                .put("path", path.toString())
                .put("line", line)
                .put("character", character),
            timeout,
            cancellation);
    return relativizeLspText(output, workdir);
  }

  String workspaceSymbols(
      Path workdir,
      Path path,
      String query,
      int limit,
      Duration timeout,
      ChildProcessRunner.CancellationCheck cancellation)
      throws Exception {
    String output =
        invokeBridge(
            workdir,
            request("workspace_symbols")
                .put("workdir", workdir.toString())
                .put("path", path.toString())
                .put("query", query)
                .put("limit", limit),
            timeout,
            cancellation);
    return relativizeLspText(output, workdir);
  }

  String javaDecompile(
      Path workdir,
      Path path,
      String target,
      Duration timeout,
      ChildProcessRunner.CancellationCheck cancellation)
      throws Exception {
    if (bridgeAvailable()) {
      try {
        String output =
            invokeBridge(
                workdir,
                request("java_decompile")
                    .put("workdir", workdir.toString())
                    .put("path", path.toString())
                    .put("target", target),
                timeout,
                cancellation);
        return relativizeLspText(output, workdir);
      } catch (ChildProcessRunner.ChildProcessException bridgeError) {
        String fallback = tryJavap(workdir, path, target, timeout, cancellation);
        if (fallback != null) {
          return fallback
              + "\n\n(note: LSP bridge failed; used javap fallback: "
              + bridgeError.getMessage()
              + ")";
        }
        throw bridgeError;
      } catch (IllegalStateException bridgeError) {
        String fallback = tryJavap(workdir, path, target, timeout, cancellation);
        if (fallback != null) {
          return fallback
              + "\n\n(note: LSP bridge failed; used javap fallback: "
              + bridgeError.getMessage()
              + ")";
        }
        throw bridgeError;
      }
    }
    String fallback = tryJavap(workdir, path, target, timeout, cancellation);
    if (fallback != null) {
      return fallback + "\n\n(note: LSP bridge unavailable; used javap fallback)";
    }
    throw new IllegalStateException(
        UNAVAILABLE_MESSAGE
            + " javap fallback could not resolve target: "
            + summarizeTarget(target));
  }

  static String relativizeLspText(String text, Path workdir) {
    if (text == null || text.isBlank() || workdir == null) {
      return text;
    }
    String workdirStr = workdir.toString().replace('\\', '/');
    if (!workdirStr.endsWith("/")) {
      workdirStr += "/";
    }
    String fileUriPrefix = "file://" + workdirStr;
    String result = text.replace('\\', '/');
    if (result.contains(fileUriPrefix)) {
      result = result.replace(fileUriPrefix, "");
    }
    if (result.contains(workdirStr)) {
      result = result.replace(workdirStr, "");
    }
    return result;
  }

  String tryJavap(
      Path workdir,
      Path sourcePath,
      String target,
      Duration timeout,
      ChildProcessRunner.CancellationCheck cancellation)
      throws Exception {
    ResolvedClass resolved = resolveClassTarget(target, sourcePath);
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
    ChildProcessRunner.Result result =
        ChildProcessRunner.run(command, workdir, null, timeout, cancellation);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "javap failed for "
              + resolved.className()
              + " (exit "
              + result.exitCode()
              + "): "
              + result.merged().trim());
    }
    return result.stdout();
  }

  private ObjectNode request(String op) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("op", op);
    return node;
  }

  private String invokeBridge(
      Path workdir,
      ObjectNode request,
      Duration timeout,
      ChildProcessRunner.CancellationCheck cancellation)
      throws Exception {
    if (!bridgeAvailable()) {
      throw new IllegalStateException(UNAVAILABLE_MESSAGE);
    }
    List<String> command = shellCommand(bridgeCommand);
    ChildProcessRunner.Result result =
        ChildProcessRunner.run(
            command, workdir, MAPPER.writeValueAsBytes(request), timeout, cancellation);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "LSP bridge exited with code " + result.exitCode() + ": " + result.merged().trim());
    }
    String raw = result.stdout();
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

  static ResolvedClass resolveClassTarget(String target, Path sourcePath) {
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
      if (!classFile.isAbsolute() && sourcePath != null) {
        Path parent = sourcePath.getParent();
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
    // 不带 jdt uri 的 "String (Class) - ..." 风格
    int paren = value.indexOf(" (");
    if (paren > 0) {
      String candidate = value.substring(0, paren).trim();
      if (candidate.matches("[A-Za-z_][\\w.$]*")) {
        // 不带包名的裸简单类名仍会尝试通过 javap bootstrap 路径解析
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
