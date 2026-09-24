package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** {@link PlatformReadToolExecutor} 的单元测试。 */
class PlatformReadToolExecutorTest {

  private PlatformSkillContentReader skillReader;
  private PlatformResourceContentReader resourceReader;
  private ObjectMapper objectMapper;
  private PlatformReadToolExecutor executor;
  private ToolExecutionListener listener;

  @BeforeEach
  void setUp() {
    skillReader = mock(PlatformSkillContentReader.class);
    resourceReader = mock(PlatformResourceContentReader.class);
    objectMapper = new ObjectMapper();
    executor = new PlatformReadToolExecutor(skillReader, resourceReader, objectMapper);
    listener = mock(ToolExecutionListener.class);
  }

  /** 传入空的 request 或 listener 时抛出 NullPointerException */
  @Test
  void nullRequestOrListenerThrowsException() {
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    assertThrows(NullPointerException.class, () -> executor.read(null, listener));
    assertThrows(NullPointerException.class, () -> executor.read(request, null));
  }

  /**
   * Skill URI 读取必须应用 offset/limit 窗口：与 daemon {@code fs.read} 相同的分页语义不能只做在本地路径分支。
   *
   * <p>畸形 arguments JSON 无法在 executable 边界出现（{@code ToolCall} 构造时已要求 JSON object），因此这里只覆盖可达的分页契约。
   */
  @Test
  void skillUriAppliesOffsetAndLimitWindow() {
    byte[] bytes = "a\nb\nc\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md")).thenReturn(bytes);

    ToolExecutionRequest request =
        mockRequest(
            "{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\",\"offset\":2,\"limit\":1}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertFalse(result.error());
    String text = firstText(result);
    assertTrue(text.contains("2|b"));
    assertFalse(text.contains("1|a"));
    assertFalse(text.contains("3|c"));
  }

  /** Skill URI 读取遇到二进制内容时返回错误结果，而不是把乱码交给模型 */
  @Test
  void skillUriWithBinaryContentCompletesWithError() {
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md"))
        .thenReturn(new byte[] {0x00, 0x01, 0x02});

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("file appears to be binary"));
  }

  /** 缺失 path 参数时返回错误结果 */
  @Test
  void missingPathCompletesWithError() {
    ToolExecutionRequest request = mockRequest("{}", null);

    ToolExecutionHandle handle = executor.read(request, listener);
    assertSame(CompletedToolExecutionHandle.INSTANCE, handle);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("path must be a non-blank string"));
  }

  /** offset/limit/column_offset 为非正整数时返回错误结果 */
  @Test
  void invalidIntegerArgumentsCompleteWithError() {
    ToolExecutionRequest request = mockRequest("{\"path\":\"file.txt\",\"offset\":0}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("offset must be a positive integer"));
  }

  /** kkstudio URI 指定 workdir 时拒绝并返回错误结果 */
  @Test
  void kkstudioUriWithWorkdirCompletesWithError() {
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/p/s/SKILL.md\",\"workdir\":\"/tmp\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("workdir must be omitted for kkstudio: URIs"));
  }

  /** kkstudio Skill URI 无需 Environment 即可成功读取并格式化 */
  @Test
  void kkstudioSkillUriSucceedsWithoutEnvironment() {
    byte[] bytes = "# Dev Skill\nline2\n".getBytes(StandardCharsets.UTF_8);
    when(skillReader.readSkillFile("pkg", "dev", "SKILL.md")).thenReturn(bytes);

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/skills/pkg/dev/SKILL.md\"}", null);

    ToolExecutionHandle handle = executor.read(request, listener);
    assertSame(CompletedToolExecutionHandle.INSTANCE, handle);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertFalse(result.error());
    assertTrue(firstText(result).contains("path: kkstudio:/skills/pkg/dev/SKILL.md"));
    assertTrue(firstText(result).contains("1|# Dev Skill"));
    assertTrue(firstText(result).contains("2|line2"));
  }

  /** kkstudio Skill URI 缺失段时返回不支持错误 */
  @Test
  void kkstudioSkillUriWithMissingSegmentsCompletesWithError() {
    ToolExecutionRequest request = mockRequest("{\"path\":\"kkstudio:/skills/pkg\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("unsupported kkstudio: URI: kkstudio:/skills/pkg"));
  }

  /** kkstudio Resource URI 使用规范 UUID 成功读取并格式化 */
  @Test
  void kkstudioResourceUriSucceedsWithCanonicalUuid() {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.threadId()).thenReturn(threadId);

    when(resourceReader.readResourceText(
            threadId, blobId, null, null, null, "kkstudio:/resources/" + blobId))
        .thenReturn("path: kkstudio:/resources/" + blobId + "\n\n1|blob-data");

    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", context);

    ToolExecutionHandle handle = executor.read(request, listener);
    assertSame(CompletedToolExecutionHandle.INSTANCE, handle);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertFalse(result.error());
    assertTrue(firstText(result).contains("path: kkstudio:/resources/" + blobId));
    assertTrue(firstText(result).contains("1|blob-data"));
  }

  /** kkstudio Resource URI 使用非规范 UUID 时返回错误结果 */
  @Test
  void kkstudioResourceUriWithNonCanonicalUuidCompletesWithError() {
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/not-a-uuid\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("invalid resource blobId"));
  }

  /** kkstudio Resource URI 缺失 execution context 时返回错误结果 */
  @Test
  void kkstudioResourceUriWithoutContextCompletesWithError() {
    UUID blobId = UUID.randomUUID();
    ToolExecutionRequest request =
        mockRequest("{\"path\":\"kkstudio:/resources/" + blobId + "\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("execution context with threadId is required"));
  }

  /** 不支持的 kkstudio URI 形式返回错误结果 */
  @Test
  void unsupportedKkstudioUriCompletesWithError() {
    ToolExecutionRequest request = mockRequest("{\"path\":\"kkstudio:/unknown/foo\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("unsupported kkstudio: URI: kkstudio:/unknown/foo"));
  }

  /** 远程 HTTP/HTTPS URI 拒绝透传并返回错误结果 */
  @Test
  void remoteSchemeUriCompletesWithError() {
    ToolExecutionRequest request = mockRequest("{\"path\":\"https://example.com/file.txt\"}", null);

    executor.read(request, listener);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("Platform does not support remote URI fetching"));
  }

  /** 本地文件路径且存在 Environment 时正确委托执行 */
  @Test
  void localPathWithEnvironmentDelegatesExecution() {
    BoundEnvironment environment = mock(BoundEnvironment.class);
    ToolExecutionHandle expectedHandle = mock(ToolExecutionHandle.class);
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.of(environment));

    ToolExecutionRequest request = mockRequest("{\"path\":\"src/main.rs\"}", context);
    when(environment.execute(any(), eq(request), eq(listener))).thenReturn(expectedHandle);

    ToolExecutionHandle handle = executor.read(request, listener);
    assertSame(expectedHandle, handle);
    verify(environment).execute(any(), eq(request), eq(listener));
  }

  /** 本地文件路径但无 Environment 时返回错误结果 */
  @Test
  void localPathWithoutEnvironmentCompletesWithError() {
    ToolExecutionContext context = mock(ToolExecutionContext.class);
    when(context.environment()).thenReturn(Optional.empty());

    ToolExecutionRequest request = mockRequest("{\"path\":\"src/main.rs\"}", context);

    ToolExecutionHandle handle = executor.read(request, listener);
    assertSame(CompletedToolExecutionHandle.INSTANCE, handle);

    ArgumentCaptor<ToolResult> captor = ArgumentCaptor.forClass(ToolResult.class);
    verify(listener).onComplete(captor.capture());
    ToolResult result = captor.getValue();
    assertTrue(result.error());
    assertTrue(firstText(result).contains("No environment bound in execution context"));
  }

  private static ToolExecutionRequest mockRequest(
      String argumentsJson, ToolExecutionContext context) {
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    ToolCall call = new ToolCall("call-1", "read", argumentsJson);
    when(request.call()).thenReturn(call);
    when(request.context()).thenReturn(context);
    return request;
  }

  private static String firstText(ToolResult result) {
    assertNotNull(result);
    assertFalse(result.contents().isEmpty());
    assertTrue(result.contents().get(0) instanceof TextResultContent);
    return ((TextResultContent) result.contents().get(0)).text();
  }
}
