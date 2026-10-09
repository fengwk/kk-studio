package fun.fengwk.kkstudio.harness.daemon.coding;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个 stdio 语言服务器连接：进程、JSON-RPC 会话、服务能力、文档同步与三类查询。
 *
 * <p>协议层由标准 LSP 客户端库承担（JSON-RPC 分帧、请求/响应/服务端请求分发），本类只负责进程生命周期、位置编码换算、文档同步与结果格式化。
 *
 * <p>关闭顺序固定为 {@code shutdown} 请求、{@code exit} 通知、有界等待、必要时强制终止整棵进程树；实例幂等，可在初始化中途被清理。
 */
final class LspClient {

  private static final int STDERR_TAIL_BYTES = 8 * 1024;
  private static final long PROCESS_PROBE_MILLIS = 200;
  private static final String JAVA_CLASS_FILE_CONTENTS = "java/classFileContents";
  private static final String JAVA_DECOMPILE_COMMAND = "java.decompile";
  private static final Pattern JDT_URI = Pattern.compile("jdt://\\S+");

  /** 文档同步使用的 LSP languageId；未覆盖的扩展名回退服务器 id。 */
  private static final Map<String, String> LANGUAGE_IDS =
      Map.ofEntries(
          Map.entry(".ts", "typescript"),
          Map.entry(".tsx", "typescriptreact"),
          Map.entry(".mts", "typescript"),
          Map.entry(".cts", "typescript"),
          Map.entry(".js", "javascript"),
          Map.entry(".jsx", "javascriptreact"),
          Map.entry(".mjs", "javascript"),
          Map.entry(".cjs", "javascript"),
          Map.entry(".java", "java"),
          Map.entry(".go", "go"),
          Map.entry(".py", "python"),
          Map.entry(".pyi", "python"),
          Map.entry(".c", "c"),
          Map.entry(".h", "c"),
          Map.entry(".cpp", "cpp"),
          Map.entry(".cc", "cpp"),
          Map.entry(".hpp", "cpp"));

  private final LspServerConfig server;
  private final Path root;
  private final Process process;

  /** 承载服务器命令的执行范围：命令的自然退出事实与整组收敛都由它回答。 */
  private final ProcessScope scope;

  private final Launcher<JdtlsServer> launcher;
  private final JdtlsServer remote;
  private final Map<Path, Document> documents = new ConcurrentHashMap<>();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile ServerCapabilities capabilities;
  private volatile String positionEncoding = PositionEncodingKind.UTF16;

  private LspClient(
      LspServerConfig server, Path root, ProcessScope scope, Launcher<JdtlsServer> launcher) {
    this.server = server;
    this.root = root;
    this.scope = scope;
    this.process = scope.process();
    this.launcher = launcher;
    this.remote = launcher.getRemoteProxy();
  }

  /**
   * 启动服务器进程并建立 stdio JSON-RPC 会话，但尚未握手。
   *
   * <p>服务器运行在一个 OS 级执行范围里（双向标准流），因此「服务器退出」与「整棵进程范围收敛」都由范围回答；调用方随后必须调用 {@link
   * #initialize(Duration)}。启动失败会终止整棵范围（含服务器自己派生的后代），绝不留下半初始化的进程。
   *
   * @param executable 已解析的绝对可执行文件，用于替换命令首元素（确保 {@code ~}/{@code $HOME} 展开生效）
   */
  static LspClient launch(
      LspServerConfig server, Path root, String executable, ExecutorService dispatch) {
    List<String> command = new ArrayList<>(server.command());
    command.set(0, executable);
    ProcessScope scope = startScope(server, root, command);
    Process process = scope.process();
    ByteTailBuffer stderrTail = new ByteTailBuffer(STDERR_TAIL_BYTES);
    try {
      // stderr 排空与后续初始化同属本进程所有权：提交被拒也必须终止整棵范围，不能把泄漏留给调用方。
      dispatch.submit(() -> drain(process.getErrorStream(), stderrTail));
      if (scope.awaitNaturalExit(PROCESS_PROBE_MILLIS)) {
        throw new ToolServiceFailureException(earlyExitMessage(server, command, scope));
      }
      LspClient client =
          new LspClient(
              server,
              root,
              scope,
              new LSPLauncher.Builder<JdtlsServer>()
                  .setLocalService(new ClientEndpoint(root))
                  /*
                   * 远端接口必须声明 jdtls 的扩展请求：lsp4j 只有在能按 id 解析出方法返回类型时才会反序列化结果，
                   * 否则通用 request(...) 会把结果解析成 null。
                   */
                  .setRemoteInterfaces(List.of(JdtlsServer.class))
                  .setInput(process.getInputStream())
                  .setOutput(process.getOutputStream())
                  .setExecutorService(dispatch)
                  .wrapMessages(Function.identity())
                  .create());
      client.launcher.startListening();
      return client;
    } catch (RuntimeException error) {
      scope.close();
      throw error;
    }
  }

  /** 启动承载服务器命令的执行范围；建立范围之前的任何失败都是「服务器无法启动」。 */
  private static ProcessScope startScope(LspServerConfig server, Path root, List<String> command) {
    try {
      return ProcessScope.startDuplex(root, command);
    } catch (IOException | RuntimeException error) {
      // 只回显固定 server id 与可信的启动命令二进制名：不输出原始启动错误（可能内联 OS 文本与命令参数）或目录。
      throw new ToolServiceFailureException(
          "LSP server '" + server.id() + "' cannot be started: " + command.getFirst(), error);
    }
  }

  /**
   * 服务器在握手之前就结束（或根本没有启动起来）时的失败说明。
   *
   * <p>两类事实必须分开：范围回答启动失败（可执行文件不存在、不可执行）说明命令从未跑起来；否则就是命令自己跑过又退出，退出码只能取命令自己发布的那个。 模型可见文案只保留固定 server
   * id、可信的二进制名与已知退出码，绝不携带服务器 stderr、命令行参数或原始启动错误。
   */
  private static String earlyExitMessage(
      LspServerConfig server, List<String> command, ProcessScope scope) {
    if (scope.startFailure() != null) {
      return "LSP server '" + server.id() + "' cannot be started: " + command.getFirst();
    }
    return "LSP server '"
        + server.id()
        + "' exited before initialization (exit code "
        + knownExitCode(scope)
        + ")";
  }

  /** 已知退出码；命令尚未发布退出码时如实说「未知」，不拿别的数字顶替。 */
  private static String knownExitCode(ProcessScope scope) {
    Integer exitCode = scope.naturalExitCode();
    return exitCode == null ? "unknown" : exitCode.toString();
  }

  /**
   * 完成 {@code initialize}/{@code initialized} 握手并记录服务能力与位置编码。
   *
   * <p>失败时终止整棵进程树，因此半初始化的实例不会留在池中。
   */
  void initialize(Duration timeout) throws Exception {
    InitializeParams params = new InitializeParams();
    params.setProcessId((int) ProcessHandle.current().pid());
    String rootUri = root.toUri().toString();
    params.setRootUri(rootUri);
    params.setRootPath(root.toString());
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(rootUri, "workspace")));
    params.setCapabilities(clientCapabilities());
    if (isJdtls()) {
      // jdtls 只在客户端声明 classFileContents 支持时才允许 java/classFileContents 请求。
      params.setInitializationOptions(
          Map.of("extendedClientCapabilities", Map.of("classFileContentsSupport", true)));
    }
    try {
      InitializeResult result = await(remote.initialize(params), timeout, "initialize");
      ServerCapabilities advertised = result == null ? null : result.getCapabilities();
      this.capabilities = advertised;
      String encoding = advertised == null ? null : advertised.getPositionEncoding();
      if (encoding != null && !encoding.isBlank()) {
        this.positionEncoding = encoding;
      }
      remote.initialized();
    } catch (Exception error) {
      stop(Duration.ZERO);
      throw error;
    }
  }

  private static ClientCapabilities clientCapabilities() {
    ClientCapabilities capabilities = new ClientCapabilities();
    GeneralClientCapabilities general = new GeneralClientCapabilities();
    general.setPositionEncodings(List.of(PositionEncodingKind.UTF16, PositionEncodingKind.UTF8));
    capabilities.setGeneral(general);
    WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
    workspace.setWorkspaceFolders(true);
    workspace.setConfiguration(false);
    workspace.setApplyEdit(false);
    capabilities.setWorkspace(workspace);
    SynchronizationCapabilities synchronization = new SynchronizationCapabilities();
    synchronization.setWillSave(false);
    synchronization.setWillSaveWaitUntil(false);
    synchronization.setDidSave(true);
    TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
    textDocument.setSynchronization(synchronization);
    DefinitionCapabilities definition = new DefinitionCapabilities();
    definition.setLinkSupport(true);
    textDocument.setDefinition(definition);
    capabilities.setTextDocument(textDocument);
    return capabilities;
  }

  /** 服务器进程是否仍在运行；自然退出、传输失败或已关闭的实例不再被复用。 */
  boolean isAlive() {
    return !stopped.get() && running();
  }

  /**
   * 服务器命令是否仍在运行：范围没有发布它的退出码，承载它的进程也还活着。
   *
   * <p>只看 helper 的存活是不够的：命令退出后 helper 还要收敛整组才会结束，那段时间里「服务器还在跑」会是错的。
   */
  private boolean running() {
    return scope.naturalExitCode() == null && process.isAlive();
  }

  boolean supports(String method) {
    ServerCapabilities advertised = capabilities;
    if (advertised == null) {
      return false;
    }
    return switch (method) {
      case "textDocument/definition" -> advertised(advertised.getDefinitionProvider());
      case "workspace/symbol" -> advertised(advertised.getWorkspaceSymbolProvider());
      default -> isJdtls();
    };
  }

  private static boolean advertised(Either<Boolean, ?> provider) {
    return provider != null && (provider.isLeft() ? Boolean.TRUE.equals(provider.getLeft()) : true);
  }

  /** 查询前把磁盘上的当前内容同步给服务器：首次打开发送 {@code didOpen}，内容变化发送全量 {@code didChange} 与 {@code didSave}。 */
  void sync(Path file) {
    requireRunning();
    Path target = file.toAbsolutePath().normalize();
    Document document = documents.get(target);
    long modified;
    long size;
    try {
      modified = Files.getLastModifiedTime(target).toMillis();
      size = Files.size(target);
    } catch (IOException error) {
      // sync 可能发生在同一服务器已 didOpen 之后：读取失败按服务失败处理，且不输出路径或原始 OS 文本。
      throw new ToolServiceFailureException(
          "LSP request failed: the source file could not be read", error);
    }
    if (document != null && document.modified() == modified && document.size() == size) {
      return;
    }
    String text = readText(target);
    String uri = target.toUri().toString();
    if (document == null) {
      remote
          .getTextDocumentService()
          .didOpen(
              new DidOpenTextDocumentParams(
                  new TextDocumentItem(uri, languageId(target), 1, text)));
      documents.put(target, new Document(uri, 1, text, modified, size));
      return;
    }
    if (text.equals(document.text())) {
      documents.put(target, new Document(uri, document.version(), text, modified, size));
      return;
    }
    int version = document.version() + 1;
    remote
        .getTextDocumentService()
        .didChange(
            new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(text))));
    remote
        .getTextDocumentService()
        .didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri)));
    documents.put(target, new Document(uri, version, text, modified, size));
  }

  /** 已打开文档的当前版本；写工具改完文件后可直接刷新，不需要重新查询。 */
  void refresh(Path file) {
    if (!isAlive()) {
      return;
    }
    Path target = file.toAbsolutePath().normalize();
    if (!documents.containsKey(target)) {
      return;
    }
    sync(target);
  }

  List<String> definition(Path file, int line, int character, Duration timeout) throws Exception {
    requireRunning();
    if (!supports("textDocument/definition")) {
      throw new ToolServiceFailureException(
          "LSP server '" + server.id() + "' does not advertise go-to-definition support.");
    }
    sync(file);
    Document document = documents.get(file.toAbsolutePath().normalize());
    Position position = position(document, line, character);
    Either<List<? extends Location>, List<? extends LocationLink>> result =
        await(
            remote
                .getTextDocumentService()
                .definition(
                    new DefinitionParams(new TextDocumentIdentifier(document.uri()), position)),
            timeout,
            "textDocument/definition");
    return formatLocations(result);
  }

  List<String> workspaceSymbols(String query, int limit, Duration timeout) throws Exception {
    requireRunning();
    if (!supports("workspace/symbol")) {
      throw new ToolServiceFailureException(
          "LSP server '" + server.id() + "' does not advertise workspace symbol support.");
    }
    Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
        await(
            remote.getWorkspaceService().symbol(new WorkspaceSymbolParams(query)),
            timeout,
            "workspace/symbol");
    return formatSymbols(result, limit);
  }

  /**
   * 反编译 Java class：{@code jdt://} 目标走 jdtls 的 {@code java/classFileContents}，绝对本地 class 文件或 {@code
   * file:} URI 走 {@code java.decompile} 命令。
   *
   * <p>返回的源码原样透传，不做任何路径改写。
   */
  String javaDecompile(String target, Duration timeout) throws Exception {
    requireRunning();
    Matcher matcher = JDT_URI.matcher(target);
    if (matcher.find()) {
      if (!supports(JAVA_CLASS_FILE_CONTENTS)) {
        throw new ToolServiceFailureException("lsp_java_decompile is only supported by jdtls.");
      }
      String result =
          await(
              remote.classFileContents(Map.of("uri", matcher.group())),
              timeout,
              JAVA_CLASS_FILE_CONTENTS);
      return requireDecompiledSource(result, target);
    }
    Path path = resolveLocalTarget(target);
    if (!Files.isRegularFile(path)) {
      throw new ToolInputRejectedException("target is not a readable class file: " + path);
    }
    Object result =
        await(
            remote
                .getWorkspaceService()
                .executeCommand(
                    new ExecuteCommandParams(
                        JAVA_DECOMPILE_COMMAND, List.of(path.toUri().toString()))),
            timeout,
            JAVA_DECOMPILE_COMMAND);
    return requireDecompiledSource(result, target);
  }

  /** 服务器返回的源码必须是正文：空结果按可操作错误处理，不用字节码冒充源码。 */
  private static String requireDecompiledSource(Object result, String target) {
    if (result instanceof String source && !source.isBlank()) {
      return source;
    }
    throw new ToolServiceFailureException(
        "Could not load or decompile class for target: " + target);
  }

  /**
   * 解析本地 class 目标：完整符号行中的 {@code jdt://} URI 由调用方先行提取；{@code file:} URI 与绝对路径直接使用；
   * 相对路径一律拒绝，绝不回退到守护进程的 cwd。
   */
  private static Path resolveLocalTarget(String target) {
    String trimmed = target.trim();
    if (trimmed.startsWith("file:")) {
      try {
        return Path.of(URI.create(trimmed)).normalize();
      } catch (IllegalArgumentException error) {
        throw new ToolInputRejectedException("invalid class target URI: " + target, error);
      }
    }
    Path path;
    try {
      path = Path.of(trimmed);
    } catch (RuntimeException error) {
      throw new ToolInputRejectedException("invalid class target: " + target, error);
    }
    if (!path.isAbsolute()) {
      throw new ToolInputRejectedException(
          "target must be an absolute class path, a file: URI, or a jdt:// URI: " + target);
    }
    return path.normalize();
  }

  /**
   * 关闭连接：先发 {@code shutdown} 并等待，再发 {@code exit}，在有界宽限内等待进程自行退出，最后强制终止整棵进程树。
   *
   * <p>幂等：重复调用只执行一次真实关闭；初始化中的实例也能立即收敛。
   */
  void stop(Duration grace) {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    try {
      if (running()) {
        long graceMillis = Math.max(0, grace.toMillis());
        try {
          await(remote.shutdown(), Duration.ofMillis(graceMillis), "shutdown", false);
        } catch (Exception ignored) {
          // shutdown 失败不影响后续 exit 与强制终止。
        }
        try {
          remote.exit();
        } catch (RuntimeException ignored) {
          // 通道已断开时 exit 通知失败是正常路径。
        }
        waitForExit(process, graceMillis);
      }
    } finally {
      documents.clear();
      // 关闭整棵范围：服务器自己派生的后代也必须在这次调用里消失，而不是留给操作系统。
      scope.close();
    }
  }

  /** 有界等待进程退出；被中断时保留中断标记并视为未退出。 */
  private static boolean waitForExit(Process process, long millis) {
    try {
      return process.waitFor(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void requireRunning() {
    if (stopped.get()) {
      throw new ToolServiceFailureException("LSP client stopped");
    }
    if (!running()) {
      throw new ToolServiceFailureException(
          "LSP server '" + server.id() + "' exited (exit code " + knownExitCode(scope) + ")");
    }
  }

  private <T> T await(CompletableFuture<T> future, Duration timeout, String description)
      throws Exception {
    return await(future, timeout, description, true);
  }

  /**
   * @param requireAlive {@code false} 用于已经标记停止的关闭请求：它只等待应答，不再校验存活。
   */
  private <T> T await(
      CompletableFuture<T> future, Duration timeout, String description, boolean requireAlive)
      throws Exception {
    Objects.requireNonNull(future, description);
    long millis = Math.max(1, timeout.toMillis());
    try {
      T result = future.get(millis, TimeUnit.MILLISECONDS);
      if (requireAlive) {
        requireRunning();
      }
      return result;
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new ToolServiceFailureException(
          "LSP request timed out after " + millis + "ms (" + description + ").", error);
    } catch (InterruptedException error) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw error;
    } catch (ExecutionException error) {
      // 结果不可确认：只声明请求失败与操作名，不附带 cause message（远端/OS 原始文本可能内联凭据）。
      Throwable cause = error.getCause() == null ? error : error.getCause();
      throw new ToolServiceFailureException("LSP request failed (" + description + ")", cause);
    } catch (CancellationException error) {
      throw new ToolServiceFailureException(
          "LSP client stopped before the request finished (" + description + ").", error);
    }
  }

  /** 1-based 行号与码点列偏移换成协商编码下的 LSP 位置。 */
  private Position position(Document document, int line, int character) {
    String text = document.text();
    int start = 0;
    for (int current = 1; current < line; current++) {
      int newline = text.indexOf('\n', start);
      if (newline < 0) {
        throw new ToolServiceFailureException(
            "line " + line + " is beyond the end of the synchronized document");
      }
      start = newline + 1;
    }
    int newline = text.indexOf('\n', start);
    int lineEnd = newline < 0 ? text.length() : newline;
    if (lineEnd > start && text.charAt(lineEnd - 1) == '\r') {
      lineEnd--;
    }
    String lineText = text.substring(start, lineEnd);
    int codePoints = lineText.codePointCount(0, lineText.length());
    if (character > codePoints) {
      throw new ToolServiceFailureException(
          "character "
              + character
              + " is beyond line "
              + line
              + " of the synchronized document ("
              + codePoints
              + " code points)");
    }
    String prefix = lineText.substring(0, lineText.offsetByCodePoints(0, character));
    int encoded =
        switch (positionEncoding) {
          case PositionEncodingKind.UTF8 -> prefix.getBytes(StandardCharsets.UTF_8).length;
          case PositionEncodingKind.UTF32 -> prefix.codePointCount(0, prefix.length());
          default -> prefix.length();
        };
    return new Position(line - 1, encoded);
  }

  private static List<String> formatLocations(
      Either<List<? extends Location>, List<? extends LocationLink>> result) {
    List<String> formatted = new ArrayList<>();
    if (result != null && result.isLeft() && result.getLeft() != null) {
      for (Location location : result.getLeft()) {
        formatted.add(locationText(location.getUri(), location.getRange()));
      }
    } else if (result != null && result.isRight() && result.getRight() != null) {
      for (LocationLink link : result.getRight()) {
        formatted.add(
            locationText(
                link.getTargetUri(),
                link.getTargetSelectionRange() != null
                    ? link.getTargetSelectionRange()
                    : link.getTargetRange()));
      }
    }
    return formatted.isEmpty() ? List.of("No results found") : formatted;
  }

  /** 本地文件 URI 转成真实路径打印；{@code jdt://} 等虚拟 URI 原样保留。 */
  private static String locationText(String uri, Range range) {
    if (uri == null) {
      return "unknown";
    }
    String location = uri;
    if (uri.startsWith("file:")) {
      try {
        location = Path.of(URI.create(uri)).toString();
      } catch (IllegalArgumentException ignored) {
        location = uri;
      }
    }
    if (range == null || range.getStart() == null) {
      return location;
    }
    return location
        + ":"
        + (range.getStart().getLine() + 1)
        + ":"
        + range.getStart().getCharacter();
  }

  private static List<String> formatSymbols(
      Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result,
      int limit) {
    List<String> symbols = new ArrayList<>();
    if (limit <= 0) {
      return List.of("No symbols found");
    }
    if (result != null && result.isLeft() && result.getLeft() != null) {
      for (SymbolInformation symbol : result.getLeft()) {
        symbols.add(
            symbolLine(symbol.getName(), symbol.getKind(), symbolUri(symbol.getLocation())));
        if (symbols.size() == limit) {
          break;
        }
      }
    } else if (result != null && result.isRight() && result.getRight() != null) {
      for (WorkspaceSymbol symbol : result.getRight()) {
        symbols.add(
            symbolLine(symbol.getName(), symbol.getKind(), symbolUri(symbol.getLocation())));
        if (symbols.size() == limit) {
          break;
        }
      }
    }
    return symbols.isEmpty() ? List.of("No symbols found") : symbols;
  }

  private static String symbolLine(String name, SymbolKind kind, String uri) {
    return (name == null ? "unknown" : name)
        + " ("
        + (kind == null ? "unknown" : kind.name())
        + ") - "
        + (uri == null ? "unknown" : uri);
  }

  private static String symbolUri(Location location) {
    return location == null ? null : location.getUri();
  }

  private static String symbolUri(Either<Location, WorkspaceSymbolLocation> location) {
    if (location == null) {
      return null;
    }
    if (location.isLeft()) {
      return symbolUri(location.getLeft());
    }
    return location.getRight() == null ? null : location.getRight().getUri();
  }

  private String languageId(Path file) {
    Path name = file.getFileName();
    String value = name == null ? "" : name.toString();
    int dot = value.lastIndexOf('.');
    if (dot >= 0 && dot < value.length() - 1) {
      String language = LANGUAGE_IDS.get(value.substring(dot).toLowerCase(Locale.ROOT));
      if (language != null) {
        return language;
      }
    }
    return server.id();
  }

  private static String readText(Path file) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException error) {
      throw new ToolServiceFailureException("the source file could not be read", error);
    }
    try {
      return TextFileCodec.decode(bytes).text();
    } catch (IllegalArgumentException error) {
      throw new ToolServiceFailureException(
          "LSP request failed: the source file is not valid text", error);
    }
  }

  private boolean isJdtls() {
    return isJdtlsCommand(server.command());
  }

  /** 命令任意元素的可执行文件基名是 jdtls（含 {@code jdtls.cmd} 等包装脚本）时按 jdtls 处理。 */
  static boolean isJdtlsCommand(List<String> command) {
    return command.stream().anyMatch(LspClient::isJdtlsExecutable);
  }

  private static boolean isJdtlsExecutable(String value) {
    String name = value.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    String lower = name.toLowerCase(Locale.ROOT);
    for (String suffix : List.of(".exe", ".cmd", ".bat")) {
      if (lower.endsWith(suffix)) {
        lower = lower.substring(0, lower.length() - suffix.length());
      }
    }
    return lower.equals("jdtls");
  }

  /** 有界诊断尾部：只用于错误信息，不随服务器输出总量增长。 */
  private static void drain(InputStream stream, ByteTailBuffer tail) {
    try (InputStream input = stream) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        tail.append(buffer, 0, read);
      }
    } catch (IOException ignored) {
      // 进程退出会关闭 stderr，属于正常收敛路径。
    }
  }

  /**
   * jdtls 在 {@link LanguageServer} 之上的扩展请求。
   *
   * <p>接口必须注册成远端接口：lsp4j 依据已注册方法的返回类型反序列化结果，未注册的方法即使服务器回了结果也会被解析成 {@code null}。
   */
  interface JdtlsServer extends LanguageServer {

    /**
     * 读取 {@code jdt://} 虚拟 class 的源码。
     *
     * <p>参数是 {@code {"uri": "jdt://..."}}。
     */
    @JsonRequest("java/classFileContents")
    CompletableFuture<String> classFileContents(Map<String, String> params);
  }

  /** 已打开文档的本地事实：LSP uri、版本、同步内容与文件状态戳。 */
  private record Document(String uri, int version, String text, long modified, long size) {}

  /**
   * 服务端请求与通知的本地应答：只声明我们真正支持的能力，其余按协议回 {@code MethodNotFound}。
   *
   * <p>{@code workspace/configuration} 一律返回与请求条目数一致的空设置，让服务器使用自己的默认值；动态能力注册与进度请求接受后忽略；编辑类请求明确拒绝。
   */
  private static final class ClientEndpoint implements LanguageClient {

    private final Path root;

    private ClientEndpoint(Path root) {
      this.root = root;
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
      List<Object> settings = new ArrayList<>();
      if (params != null && params.getItems() != null) {
        params.getItems().forEach(ignored -> settings.add(Map.of()));
      }
      return CompletableFuture.completedFuture(settings);
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
      return CompletableFuture.completedFuture(
          List.of(new WorkspaceFolder(root.toUri().toString(), "workspace")));
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
      // 无动态能力注册：接受并忽略，避免服务器因未实现的请求而降级。
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
      // 无 UI 进度：进度通知本身也被忽略。
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(
        ApplyWorkspaceEditParams params) {
      return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
        ShowMessageRequestParams params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void showMessage(MessageParams params) {
      // 无 UI：服务器主动提示只作为诊断被忽略。
    }

    @Override
    public void logMessage(MessageParams params) {
      // 无日志通道：诊断由有界 stderr 尾部与请求错误承载。
    }

    @Override
    public void telemetryEvent(Object object) {
      // 不接受遥测。
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
      // 诊断不进入工具结果：本能力只回答导航类问题。
    }
  }
}
