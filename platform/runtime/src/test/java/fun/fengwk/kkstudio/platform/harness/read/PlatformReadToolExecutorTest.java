package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** {@link PlatformReadToolExecutor} 的单元测试。 */
class PlatformReadToolExecutorTest {

  private PlatformSkillContentReader skillReader;
  private PlatformResourceContentReader resourceReader;
  private ObjectMapper objectMapper;
  private PlatformReadToolExecutor executor;
  private Consumer<ToolResult> progress;

  @BeforeEach
  void setUp() throws Exception {
    skillReader = mock(PlatformSkillContentReader.class);
    resourceReader = mock(PlatformResourceContentReader.class);
    objectMapper = JsonMapper.builder().build();
    executor = new PlatformReadToolExecutor(skillReader, resourceReader, objectMapper);
    progress = mock(Consumer.class);
  }

  /** 传入空的 request 或 progress 时抛出 NullPointerException */
  @Test
  void nullRequestOrListenerThrowsException() throws Exception {
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    assertThrows(NullPointerException.class, () -> executor.read(null, progress));
    assertThrows(NullPointerException.class, () -> executor.read(request, null));
  }

  /**
   * Skill URI 读取必须应用 offset/limit 窗口：与 daemon {@code fs.read} 相同的分页语义不能只做在本地路径分支。
   *
   * <p>畸形 arguments JSON 无法在 executable 边界出现（{@code ToolCall} 构造时已要求 JSON object），因此这里只覆盖可达的分页契约。
   */
  @Test
  void skillUriAppliesOffsetAndLimitWindow() throws Exception {
    byte[] bytes = "a\nb\nc\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md")).thenReturn(bytes);

    ToolExecutionRequest request =
        mockRequest(
            "{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\",\"offset\":2,\"limit\":1}", null);

    ToolResult result = executor.read(request, progress);
    assertFalse(result.error());
    String text = firstText(result);
    assertTrue(text.contains("2|b"));
    assertFalse(text.contains("1|a"));
    assertFalse(text.contains("3|c"));
  }

  /** Skill URI 读取遇到二进制内容时返回错误结果，而不是把乱码交给模型 */
  @Test
  void skillUriWithBinaryContentCompletesWithError() throws Exception {
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md"))
        .thenReturn(new byte[] {0x00, 0x01, 0x02});

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("file appears to be binary"));
  }

  /** 缺失 path 参数时返回错误结果 */
  @Test
  void missingPathCompletesWithError() throws Exception {
    ToolExecutionRequest request = mockRequest("{}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("path must be a non-blank string"));
  }

  /** offset/limit/column_offset 为非正整数时返回错误结果 */
  @Test
  void invalidIntegerArgumentsCompleteWithError() throws Exception {
    ToolExecutionRequest request = mockRequest("{\"path\":\"file.txt\",\"offset\":0}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("offset must be a positive integer"));
  }

  /** kkstudio URI 即使携带无意义的 workdir 也不做拦截，workdir 参数被静默忽略。 */
  @Test
  void kkstudioUriIgnoresWorkdirArgument() throws Exception {
    byte[] bytes = "# Dev Skill\nline2\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("p", "s", "SKILL.md")).thenReturn(bytes);

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/p/s/SKILL.md\",\"workdir\":\"/tmp\"}", null);

    ToolResult result = executor.read(request, progress);
    assertFalse(result.error());
    assertTrue(firstText(result).contains("# Dev Skill"));
  }

  /** kkstudio Skill URI 无需 Environment 即可成功读取并格式化 */
  @Test
  void kkstudioSkillUriSucceedsWithoutEnvironment() throws Exception {
    byte[] bytes = "# Dev Skill\nline2\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md")).thenReturn(bytes);

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null);

    ToolResult result = executor.read(request, progress);
    assertFalse(result.error());
    assertTrue(firstText(result).contains("path: kkstudio:/skills/pkg/dev/SKILL.md"));
    assertTrue(firstText(result).contains("1|# Dev Skill"));
    assertTrue(firstText(result).contains("2|line2"));
  }

  /** kkstudio Skill URI 缺失段时返回不支持错误 */
  @Test
  void kkstudioSkillUriWithMissingSegmentsCompletesWithError() throws Exception {
    ToolExecutionRequest request = mockRequest("{\"path\":\"kkstudio:/skills/pkg\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("unsupported kkstudio: URI: kkstudio:/skills/pkg"));
  }

  /** kkstudio Resource URI 使用规范 UUID 成功读取并格式化 */
  @Test
  void kkstudioResourceUriSucceedsWithCanonicalUuid() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(threadId);

    when(resourceReader.readResource(
            eq(threadId),
            eq(null),
            eq("call-1"),
            eq(blobId),
            eq(null),
            eq(null),
            eq(null),
            eq("kkstudio:/resources/" + blobId),
            any(ReadDeadline.class)))
        .thenReturn(
            new ResourceRead.Text("path: kkstudio:/resources/" + blobId + "\n\n1|blob-data"));

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", context);

    ToolResult result = executor.read(request, progress);
    assertFalse(result.error());
    assertTrue(firstText(result).contains("path: kkstudio:/resources/" + blobId));
    assertTrue(firstText(result).contains("1|blob-data"));
  }

  /** kkstudio Resource URI 指向支持的媒体时返回 durable ResourceResultContent，而不是把字节塞进结果。 */
  @Test
  void kkstudioMediaResourceUriReturnsDurableResourceResult() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(threadId);
    ResourceRef ref =
        new ResourceRef(
            ResourceRef.sessionResourceUri(blobId),
            "image/png",
            blobId.toString(),
            3L,
            "0".repeat(64));
    when(resourceReader.readResource(
            eq(threadId),
            eq(null),
            eq("call-1"),
            eq(blobId),
            eq(null),
            eq(null),
            eq(null),
            eq("kkstudio:/resources/" + blobId),
            any(ReadDeadline.class)))
        .thenReturn(new ResourceRead.Media(ref, null));

    ToolResult result =
        executor.read(
            mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", context), progress);
    assertFalse(result.error());
    ResourceResultContent content =
        assertInstanceOf(ResourceResultContent.class, result.contents().get(0));
    assertEquals(blobId, content.resource().sessionBlobId());
    assertEquals("image/png", content.resource().mediaType());
  }

  /** kkstudio Resource URI 使用非规范 UUID 时返回错误结果 */
  @Test
  void kkstudioResourceUriWithNonCanonicalUuidCompletesWithError() throws Exception {
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/not-a-uuid\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("invalid resource blobId"));
  }

  /** kkstudio Resource URI 缺失 execution context 时返回错误结果 */
  @Test
  void kkstudioResourceUriWithoutContextCompletesWithError() throws Exception {
    UUID blobId = UUID.randomUUID();
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("execution context with threadId is required"));
  }

  /** 不支持的 kkstudio URI 形式返回错误结果 */
  @Test
  void unsupportedKkstudioUriCompletesWithError() throws Exception {
    ToolExecutionRequest request = mockRequest("{\"path\":\"kkstudio:/unknown/foo\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("unsupported kkstudio: URI: kkstudio:/unknown/foo"));
  }

  /** 远程 HTTP/HTTPS URI 拒绝透传并返回错误结果 */
  @Test
  void remoteSchemeUriCompletesWithError() throws Exception {
    ToolExecutionRequest request = mockRequest("{\"path\":\"https://example.com/file.txt\"}", null);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("Platform does not support remote URI fetching"));
  }

  /** 本地文件路径且存在 Environment 时正确委托执行 */
  @Test
  void localPathWithEnvironmentDelegatesExecution() throws Exception {
    BoundEnvironment environment = mock(BoundEnvironment.class);
    ToolResult expectedResult = ToolResult.error("call-1", "delegated");
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.of(environment));

    ToolExecutionRequest request = mockRequest("{\"path\":\"src/main.rs\"}", context);
    when(environment.execute(any(), eq(request), eq(progress))).thenReturn(expectedResult);

    assertSame(expectedResult, executor.read(request, progress));
    verify(environment).execute(any(), eq(request), eq(progress));
  }

  /** 本地文件路径但无 Environment 时返回错误结果 */
  @Test
  void localPathWithoutEnvironmentCompletesWithError() throws Exception {
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.empty());

    ToolExecutionRequest request = mockRequest("{\"path\":\"src/main.rs\"}", context);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("No environment bound in execution context"));
  }

  /** URI 到真实 reader 的链路必须保留同名目录和百分号字面量，不做隐式解码。 */
  @ParameterizedTest
  @ValueSource(strings = {"dev/SKILL.md", "%2e%2e/literal%20.md"})
  void skillUriReadsExactGitPath(String relativePath) throws Exception {
    SkillPackageRepository skillPackageRepository = mock(SkillPackageRepository.class);
    SkillGitCache gitCache = mock(SkillGitCache.class);
    SkillPackage pkg =
        SkillPackage.builder()
            .repositoryUrl("https://example.com/repo.git")
            .currentCommit("c1")
            .skills(List.of(new SkillManifestEntry("dev", "desc")))
            .build();
    when(skillPackageRepository.getPackage("pkg")).thenReturn(pkg);
    when(gitCache.readFile("pkg", "c1", "dev/SKILL.md"))
        .thenReturn("root".getBytes(StandardCharsets.UTF_8));
    when(gitCache.readFile("pkg", "c1", "dev/" + relativePath))
        .thenReturn("exact-file".getBytes(StandardCharsets.UTF_8));
    executor =
        new PlatformReadToolExecutor(
            new PlatformSkillContentReader(skillPackageRepository, gitCache),
            resourceReader,
            objectMapper);
    String uri = "kkstudio:/skills/pkg/dev/" + relativePath;

    ToolResult result =
        executor.read(
            mockRequest(objectMapper.writeValueAsString(Map.of("path", uri)), null), progress);
    assertFalse(result.error());
    assertTrue(firstText(result).contains("1|exact-file"));
    assertFalse(firstText(result).contains("1|root"));
    verify(gitCache).ensureCommit("pkg", "https://example.com/repo.git", "c1", null);
    verify(gitCache).readFile("pkg", "c1", "dev/" + relativePath);
    verifyNoMoreInteractions(gitCache);
  }

  /** 未声明的双斜杠 URI 必须只回调一次，且不读取 Skill、Resource 或委托 Environment。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "kkstudio://skills/pkg/dev/SKILL.md",
        "kkstudio://resources/12345678-1234-1234-1234-123456789abc"
      })
  void doubleSlashUrisAreUnsupportedWithoutSideEffects(String uri) throws Exception {
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    ToolExecutionRequest request =
        mockRequest(objectMapper.writeValueAsString(Map.of("path", uri)), context);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("unsupported kkstudio: URI: " + uri));
    verifyNoInteractions(skillReader, resourceReader, context);
  }

  /** Windows 盘符和普通路径都按原 request 委托，不误判为远程 URI。 */
  @ParameterizedTest
  @ValueSource(
      strings = {"C:/work/file.txt", "C:\\work\\file.txt", "/tmp/file.txt", "relative/file.txt"})
  void localPathsDelegateUnchanged(String path) throws Exception {
    BoundEnvironment environment = mock(BoundEnvironment.class);
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.of(environment));
    ToolResult expected = ToolResult.error("call-1", "delegated");
    ToolExecutionRequest request =
        mockRequest(objectMapper.writeValueAsString(Map.of("path", path)), context);
    when(environment.execute(any(), eq(request), eq(progress))).thenReturn(expected);

    assertSame(expected, executor.read(request, progress));
    verify(environment).execute(any(), eq(request), eq(progress));
    verifyNoInteractions(skillReader, resourceReader, progress);
  }

  /** read 调用即使携带多余的 workdir 键也直接忽略并原样委托给 Environment 执行。 */
  @Test
  void localPathWithIgnoredWorkdirDelegatesUnchanged() throws Exception {
    BoundEnvironment environment = mock(BoundEnvironment.class);
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.of(environment));
    ToolResult expected = ToolResult.error("call-1", "delegated");
    ToolExecutionRequest request =
        mockRequest(
            objectMapper.writeValueAsString(Map.of("path", "/tmp/file.txt", "workdir", "/tmp")),
            context);
    when(environment.execute(any(), eq(request), eq(progress))).thenReturn(expected);

    assertSame(expected, executor.read(request, progress));
    verify(environment).execute(any(), eq(request), eq(progress));
    verifyNoInteractions(skillReader, resourceReader, progress);
  }

  /** Resource 缺失 threadId 时不得读取；授权错误应原样映射为一次失败回调。 */
  @Test
  void resourceContextAndAuthorizationArePreserved() throws Exception {
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    UUID blobId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    String uri = "kkstudio:/resources/" + blobId;
    ToolResult missing =
        executor.read(mockRequest("{\"path\":\"" + uri + "\"}", context), progress);
    verifyNoInteractions(resourceReader);
    assertTrue(missing.error());
    assertTrue(firstText(missing).contains("execution context with threadId is required"));

    UUID threadId = UUID.randomUUID();
    when(context.threadId()).thenReturn(threadId);
    when(resourceReader.readResource(
            eq(threadId),
            eq(null),
            eq("call-1"),
            eq(blobId),
            eq(null),
            eq(null),
            eq(null),
            eq(uri),
            any(ReadDeadline.class)))
        .thenThrow(new PlatformReadException("resource not authorized"));
    ToolResult denied = executor.read(mockRequest("{\"path\":\"" + uri + "\"}", context), progress);
    assertTrue(denied.error());
    assertTrue(firstText(denied).contains("resource not authorized"));
    verify(resourceReader)
        .readResource(
            eq(threadId),
            eq(null),
            eq("call-1"),
            eq(blobId),
            eq(null),
            eq(null),
            eq(null),
            eq(uri),
            any(ReadDeadline.class));
    verifyNoMoreInteractions(resourceReader);
  }

  /** 可达的参数类型和分页边界错误必须在调用下游之前返回一次失败。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"path\":12}",
        "{\"path\":\" \"}",
        "{\"path\":\"file\",\"workdir\":12}",
        "{\"path\":\"file\",\"limit\":0}",
        "{\"path\":\"file\",\"column_offset\":0}",
        "{\"path\":\"file\",\"offset\":2147483648}",
        "{\"path\":\"file\",\"offset\":1.5}"
      })
  void invalidArgumentsHaveNoSideEffects(String json) throws Exception {
    ToolResult result = executor.read(mockRequest(json, null), progress);
    assertTrue(result.error());
    verifyNoInteractions(skillReader, resourceReader);
  }

  /** 单斜杠 URI 仍需完整非空段和规范 UUID，不能因删除兼容分支而放宽校验。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "kkstudio:/skills/pkg/dev",
        "kkstudio:/skills/pkg/dev/",
        "kkstudio:/skills/ /dev/SKILL.md",
        "kkstudio:/resources/",
        "kkstudio:/resources/id/extra",
        "kkstudio:/resources/1-1-1-1-1"
      })
  void malformedCanonicalUrisHaveNoSideEffects(String uri) throws Exception {
    ToolResult result =
        executor.read(
            mockRequest(objectMapper.writeValueAsString(Map.of("path", uri)), null), progress);
    assertTrue(result.error());
    verifyNoInteractions(skillReader, resourceReader);
  }

  /** Skill 读取的业务异常和意外异常都只回调一次，空消息保留既有 fallback。 */
  @Test
  void skillReaderFailuresCompleteOnce() throws Exception {
    List<RuntimeException> failures =
        List.of(
            new PlatformReadException("missing skill"),
            new IllegalStateException("cache failure"),
            new IllegalStateException());
    for (RuntimeException failure : failures) {
      doThrow(failure).when(skillReader).readSkillFile("pkg", "dev", "SKILL.md");
      ToolResult result =
          executor.read(
              mockRequest("{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null), progress);
      assertTrue(result.error());
      assertTrue(
          firstText(result)
              .contains(
                  failure.getMessage() == null ? "failed to read skill" : failure.getMessage()));
    }
  }

  /** Resource 的意外异常同样映射失败，保留有消息和无消息两种反馈。 */
  @Test
  void resourceUnexpectedFailuresCompleteOnce() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    String uri = "kkstudio:/resources/" + blobId;
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(threadId);
    for (RuntimeException failure :
        List.of(new IllegalStateException("blob failure"), new IllegalStateException())) {
      doThrow(failure)
          .when(resourceReader)
          .readResource(
              eq(threadId),
              eq(null),
              eq("call-1"),
              eq(blobId),
              eq(null),
              eq(null),
              eq(null),
              eq(uri),
              any(ReadDeadline.class));
      ToolResult result =
          executor.read(mockRequest("{\"path\":\"" + uri + "\"}", context), progress);
      assertTrue(result.error());
      assertTrue(
          firstText(result)
              .contains(
                  failure.getMessage() == null ? "failed to read resource" : failure.getMessage()));
    }
  }

  /** 测试意图：受管资源读取以 request.timeout 构造 ReadDeadline 并完整传递给下游。 */
  @Test
  void kkstudioResourcePassesConfiguredDeadline() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(threadId);

    ArgumentCaptor<ReadDeadline> deadlineCaptor = ArgumentCaptor.forClass(ReadDeadline.class);
    when(resourceReader.readResource(
            eq(threadId),
            eq(null),
            eq("call-1"),
            eq(blobId),
            eq(null),
            eq(null),
            eq(null),
            eq("kkstudio:/resources/" + blobId),
            deadlineCaptor.capture()))
        .thenReturn(new ResourceRead.Text("1|data"));

    Duration customTimeout = Duration.ofSeconds(45);
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", context, customTimeout);

    ToolResult delivered = executor.read(request, progress);

    assertFalse(delivered.error());
    ReadDeadline captured = deadlineCaptor.getValue();
    assertNotNull(captured);
    assertTrue(captured.remaining().compareTo(Duration.ofSeconds(40)) >= 0);
    assertTrue(captured.remaining().compareTo(customTimeout) <= 0);
  }

  /** 测试意图：Skill 读取若在读取或扫描时超过入口 deadline，必须以超时错误返回，不静默 fallback。 */
  @Test
  void skillUriTimesOutWhenDeadlineExceeded() throws Exception {
    byte[] bytes = "line1\nline2\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md"))
        .thenAnswer(
            invocation -> {
              Thread.sleep(20L);
              return bytes;
            });

    ToolExecutionRequest request =
        mockRequest(
            "{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null, Duration.ofMillis(10));

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("interrupted or timed out"));
  }

  /** 测试意图：Duration.ZERO 无期限语义按 ReadDeadline 现有可表达能力处理（抛出 budget must be positive），不静默回退为 60s。 */
  @Test
  void zeroTimeoutRejectsAccordingToExistingReadDeadlineCapability() throws Exception {
    UUID blobId = UUID.randomUUID();
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(UUID.randomUUID());

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", context, Duration.ZERO);

    ToolResult result = executor.read(request, progress);
    assertTrue(result.error());
    assertTrue(firstText(result).contains("budget must be positive"));
    verifyNoInteractions(resourceReader);
  }

  private static ToolExecutionRequest mockRequest(
      String argumentsJson, ToolExecutionContext context) {
    return mockRequest(argumentsJson, context, Duration.ofSeconds(60));
  }

  private static ToolExecutionRequest mockRequest(
      String argumentsJson, ToolExecutionContext context, Duration timeout) {
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    ToolCall call = new ToolCall("call-1", "read", argumentsJson);
    when(request.call()).thenReturn(call);
    when(request.context()).thenReturn(context);
    when(request.timeout()).thenReturn(timeout);
    return request;
  }

  private static String firstText(ToolResult result) {
    assertNotNull(result);
    assertFalse(result.contents().isEmpty());
    assertTrue(result.contents().get(0) instanceof TextResultContent);
    return ((TextResultContent) result.contents().get(0)).text();
  }
}
