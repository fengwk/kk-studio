package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditAmbiguousException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 验证 {@link CloudEditTool} 的精确编辑、CAS 控制、diff 生成以及旧字符串防泄露契约。 */
class CloudEditToolTest {

  private CloudFileSystemService mockService;
  private CloudEditTool editTool;

  @BeforeEach
  void setUp() {
    mockService = mock(CloudFileSystemService.class);
    editTool = new CloudEditTool(mockService);
  }

  @Test
  void editSingleOccurrenceSuccessfully() {
    // 意图：编辑单处文本成功生成带行号 diff 与新版本号
    CloudPath path = CloudPath.of("/knowledge/code.py");
    CloudTextRevision current =
        new CloudTextRevision(
            UUID.randomUUID(), 1L, "port = 80\n", 10L, "hash1", true, Instant.now());
    CloudTextRevision updated =
        new CloudTextRevision(
            UUID.randomUUID(), 2L, "port = 8080\n", 12L, "hash2", true, Instant.now());

    when(mockService.readCurrentText(path)).thenReturn(current);
    when(mockService.editText(path, "port = 80", "port = 8080", 1L, false)).thenReturn(updated);

    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/knowledge/code.py\",\"old_string\":\"port = 80\",\"new_string\":\"port = 8080\",\"expected_revision\":1}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("path: /knowledge/code.py"));
    assertTrue(text.contains("revision: 2"));
    assertTrue(text.contains("1|- port = 80"));
    assertTrue(text.contains("1|+ port = 8080"));
  }

  @Test
  void patternNotFoundDoesNotLeakOldString() {
    // 意图：未匹配到 old_string 时报错，且错误信息中绝不回显 old_string
    CloudPath path = CloudPath.of("/knowledge/secret.py");
    String secret = "SUPER_SECRET_TOKEN_XYZ";
    when(mockService.readCurrentText(path))
        .thenReturn(
            new CloudTextRevision(UUID.randomUUID(), 1L, "code", 4L, "h", true, Instant.now()));
    when(mockService.editText(path, secret, "replaced", 1L, false))
        .thenThrow(new CloudEditPatternNotFoundException(path));

    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/knowledge/secret.py\",\"old_string\":\""
                + secret
                + "\",\"new_string\":\"replaced\",\"expected_revision\":1}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertFalse(error.contains(secret), "Error message must not leak old_string");
    assertTrue(error.contains("Could not find old_string"));
  }

  @Test
  void ambiguousMatchesWithoutReplaceAllReturnsError() {
    // 意图：多处匹配且 replace_all=false 时报告歧义，不泄露 old_string
    CloudPath path = CloudPath.of("/knowledge/multi.py");
    when(mockService.readCurrentText(path))
        .thenReturn(
            new CloudTextRevision(UUID.randomUUID(), 1L, "a a a", 5L, "h", true, Instant.now()));
    when(mockService.editText(path, "a", "b", 1L, false))
        .thenThrow(new CloudEditAmbiguousException(path, 3));

    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/knowledge/multi.py\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":1}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("Found 3 exact matches"));
  }

  @Test
  void editUnderArtifactsIsForbidden() {
    // 意图：严禁通过 cloud_edit 修改 /.artifacts 目录及其子路径
    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/.artifacts/test.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":1}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("forbidden"));
  }

  @Test
  void identicalOldAndNewStringThrowsError() {
    // 意图：old_string 与 new_string 相同时直接拒绝
    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/knowledge/doc.txt\",\"old_string\":\"same\",\"new_string\":\"same\",\"expected_revision\":1}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("must differ"));
  }

  @Test
  void validationErrorsAndReplaceAll() {
    // 意图：缺失字段、空 old_string、非正 revision 以及 replace_all=true 成功执行
    assertTrue(executeWithRawArgs(editTool, "{\"path\":\"/doc.txt\"}").error());
    assertTrue(executeWithRawArgs(editTool, "{\"path\":\"/doc.txt\",\"old_string\":\"\"}").error());
    assertTrue(
        executeWithRawArgs(editTool, "{\"path\":\"/doc.txt\",\"old_string\":\"a\"}").error());
    assertTrue(
        executeWithRawArgs(
                editTool, "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}")
            .error());
    assertTrue(
        executeWithRawArgs(
                editTool,
                "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":0}")
            .error());
    // 严格类型校验
    assertTrue(
        executeWithRawArgs(
                editTool,
                "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":\"1\"}")
            .error());
    assertTrue(
        executeWithRawArgs(
                editTool,
                "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":1.2}")
            .error());
    assertTrue(
        executeWithRawArgs(
                editTool,
                "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":1,\"replace_all\":\"true\"}")
            .error());
    assertTrue(executeWithRawArgs(editTool, "malformed_json_str").error());

    CloudPath path = CloudPath.of("/doc.txt");
    CloudTextRevision current =
        new CloudTextRevision(UUID.randomUUID(), 1L, "a and a\n", 8L, "h1", true, Instant.now());
    CloudTextRevision updated =
        new CloudTextRevision(UUID.randomUUID(), 2L, "b and b\n", 8L, "h2", true, Instant.now());
    when(mockService.readCurrentText(path)).thenReturn(current);
    when(mockService.editText(path, "a", "b", 1L, true)).thenReturn(updated);

    ToolResult result =
        execute(
            editTool,
            "{\"path\":\"/doc.txt\",\"old_string\":\"a\",\"new_string\":\"b\",\"expected_revision\":1,\"replace_all\":true}");
    assertFalse(result.error());

    CaptureListener listener = new CaptureListener();
    String secret = "sensitive_old_key_secret_12345";
    ToolCall call =
        new ToolCall(
            "call-err",
            editTool.descriptor().name(),
            "{\"path\":\"/crash.txt\",\"old_string\":\""
                + secret
                + "\",\"new_string\":\"b\",\"expected_revision\":1}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(editTool.descriptor(), call, Duration.ofSeconds(10));
    when(mockService.readCurrentText(CloudPath.of("/crash.txt")))
        .thenThrow(new RuntimeException("read crashed with secret: " + secret));

    editTool.execute(request, listener);
    assertNotNull(listener.error);
    assertEquals("Unexpected edit failure", listener.error.getMessage());
    assertNull(listener.error.getCause());
    assertFalse(listener.error.getMessage().contains(secret));
  }

  private static ToolResult executeWithRawArgs(CloudEditTool tool, String argsJson) {
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

  private static ToolResult execute(CloudEditTool tool, String argsJson) {
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
