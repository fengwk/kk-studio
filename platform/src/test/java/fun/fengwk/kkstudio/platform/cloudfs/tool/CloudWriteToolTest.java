package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 验证 {@link CloudWriteTool} 的文件创建、CAS 版本控制以及禁止修改 Artifact 树的行为。 */
class CloudWriteToolTest {

  private CloudFileSystemService mockService;
  private CloudWriteTool writeTool;

  @BeforeEach
  void setUp() {
    mockService = mock(CloudFileSystemService.class);
    writeTool = new CloudWriteTool(mockService);
  }

  @Test
  void createNewTextFileSuccessfully() {
    // 意图：expected_revision=0 时成功创建新文本文件并返回版本 1
    CloudPath path = CloudPath.of("/knowledge/notes.md");
    CloudTextRevision rev =
        new CloudTextRevision(UUID.randomUUID(), 1L, "# Notes\n", 8L, "hash", true, Instant.now());
    when(mockService.writeText(path, "# Notes\n", 0L)).thenReturn(rev);

    ToolResult result =
        execute(
            writeTool,
            "{\"path\":\"/knowledge/notes.md\",\"content\":\"# Notes\\n\",\"expected_revision\":0}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("path: /knowledge/notes.md"));
    assertTrue(text.contains("revision: 1"));
  }

  @Test
  void replaceExistingTextFileSuccessfully() {
    // 意图：expected_revision=2 时成功覆盖现有文件并返回版本 3
    CloudPath path = CloudPath.of("/knowledge/notes.md");
    CloudTextRevision rev =
        new CloudTextRevision(UUID.randomUUID(), 3L, "# New\n", 6L, "hash", true, Instant.now());
    when(mockService.writeText(path, "# New\n", 2L)).thenReturn(rev);

    ToolResult result =
        execute(
            writeTool,
            "{\"path\":\"/knowledge/notes.md\",\"content\":\"# New\\n\",\"expected_revision\":2}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("revision: 3"));
  }

  @Test
  void writeUnderArtifactsIsForbidden() {
    // 意图：严禁通过 cloud_write 修改 /.artifacts 目录及其子路径
    ToolResult result =
        execute(
            writeTool,
            "{\"path\":\"/.artifacts/hack.txt\",\"content\":\"bad\",\"expected_revision\":0}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("forbidden"));
  }

  @Test
  void revisionConflictReturnsError() {
    // 意图：CAS 版本冲突时返回语义明确的错误
    CloudPath path = CloudPath.of("/knowledge/conflict.md");
    when(mockService.writeText(path, "text", 1L))
        .thenThrow(new CloudRevisionConflictException(path, 2L, 1L));

    ToolResult result =
        execute(
            writeTool,
            "{\"path\":\"/knowledge/conflict.md\",\"content\":\"text\",\"expected_revision\":1}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("conflict") || error.contains("Revision"));
  }

  @Test
  void validationErrorsAndUnexpectedExceptions() {
    // 意图：缺失 content、缺失 expected_revision、负数 revision 以及运行时异常走 onError
    assertTrue(executeWithRawArgs(writeTool, "{\"path\":\"/file.txt\"}").error());
    assertTrue(
        executeWithRawArgs(writeTool, "{\"path\":\"/file.txt\",\"content\":\"abc\"}").error());
    assertTrue(
        executeWithRawArgs(
                writeTool, "{\"path\":\"/file.txt\",\"content\":\"abc\",\"expected_revision\":-1}")
            .error());
    // 严格类型校验：expected_revision 为字符串或浮点数、content 非字符串、畸形 JSON
    assertTrue(
        executeWithRawArgs(
                writeTool,
                "{\"path\":\"/file.txt\",\"content\":\"abc\",\"expected_revision\":\"0\"}")
            .error());
    assertTrue(
        executeWithRawArgs(
                writeTool, "{\"path\":\"/file.txt\",\"content\":\"abc\",\"expected_revision\":1.5}")
            .error());
    assertTrue(
        executeWithRawArgs(
                writeTool, "{\"path\":\"/file.txt\",\"content\":12345,\"expected_revision\":0}")
            .error());
    assertTrue(executeWithRawArgs(writeTool, "not_valid_json").error());

    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-err",
            writeTool.descriptor().name(),
            "{\"path\":\"/crash.txt\",\"content\":\"abc\",\"expected_revision\":0}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(writeTool.descriptor(), call, Duration.ofSeconds(10));
    when(mockService.writeText(CloudPath.of("/crash.txt"), "abc", 0L))
        .thenThrow(new RuntimeException("write failed"));

    writeTool.execute(request, listener);
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("write failed"));
  }

  private static ToolResult executeWithRawArgs(CloudWriteTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn("call-raw");
    when(call.argumentsJson()).thenReturn(argsJson);
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    when(request.call()).thenReturn(call);
    tool.execute(request, listener);
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static ToolResult execute(CloudWriteTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = new ToolCall("call-test", tool.descriptor().name(), argsJson);
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(10));
    tool.execute(request, listener);
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static class CaptureListener implements ToolExecutionListener {
    ToolOutcome outcome;
    Throwable error;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      this.outcome = outcome;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }
  }
}
