package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.impl.CloudQueryServiceImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证 {@link CloudGrepTool} 的内容搜索、RE2/J 正则执行、Artifact 单文件检索与 No matches found 等契约。 */
class CloudGrepToolTest {

  private CloudFileSystemService mockService;
  private StorageBlobFileReader mockBlobReader;
  private CloudGrepTool grepTool;

  @BeforeEach
  void setUp() {
    mockService = mock(CloudFileSystemService.class);
    mockBlobReader = mock(StorageBlobFileReader.class);
    grepTool =
        new CloudGrepTool(new CloudQueryServiceImpl(mockService, Optional.of(mockBlobReader)));
  }

  @Test
  void grepDirectoryMatchesTextFiles() {
    // 意图：遍历目录检索 TEXT 文件，按 canonicalPath:line:content 格式输出
    CloudPath dirPath = CloudPath.of("/knowledge");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode doc1 =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "a.py",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of(doc1));
    when(mockService.readCurrentText(CloudPath.of("/knowledge/a.py")))
        .thenReturn(
            new CloudTextRevision(
                doc1.getId(), 1L, "def hello():\n  pass\n", 20L, "h", true, Instant.now()));

    ToolResult result = execute(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"hello\"}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("/knowledge/a.py:1:def hello():"));
  }

  @Test
  void grepExactTxtToolArtifactSuccessfully() {
    // 意图：精确的 canonical .txt Tool Artifact 可以被单独检索
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artifactPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    UUID blobId = UUID.randomUUID();
    CloudNode artifactNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            invocationId + ".txt",
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(artifactPath)).thenReturn(artifactNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(new BlobReadResult.Text("target content\nsecond line\n", "text/plain", 25L));

    ToolResult result =
        execute(grepTool, "{\"path\":\"" + artifactPath.value() + "\",\"pattern\":\"target\"}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains(artifactPath.value() + ":1:target content"));
  }

  @Test
  void grepOnArtifactDirectoryOrJsonIsForbidden() {
    // 意图：对 /.artifacts 目录或非 .txt 的 artifact 检索应被拒绝
    ToolResult dirResult = execute(grepTool, "{\"path\":\"/.artifacts\",\"pattern\":\"abc\"}");
    assertTrue(dirResult.error());

    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath jsonArtifact = ToolArtifactPath.format(threadId, invocationId, "json");
    ToolResult jsonResult =
        execute(grepTool, "{\"path\":\"" + jsonArtifact.value() + "\",\"pattern\":\"abc\"}");
    assertTrue(jsonResult.error());
  }

  @Test
  void noMatchesFoundReturnsStandardMessage() {
    // 意图：无任何匹配时必须固定返回 "No matches found"
    CloudPath dirPath = CloudPath.of("/knowledge");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of());

    ToolResult result = execute(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"nonexistent\"}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertEquals("No matches found", text);
  }

  @Test
  void limitPlusOneStopsEarly() {
    // 意图：匹配项达到 limit + 1 时立即停止，并在末尾说明限额
    CloudPath dirPath = CloudPath.of("/knowledge");
    CloudNode dirNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode doc =
        new CloudNode(
            UUID.randomUUID(),
            dirNode.getId(),
            "test.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(dirPath)).thenReturn(dirNode);
    when(mockService.listChildren(dirPath)).thenReturn(List.of(doc));
    when(mockService.readCurrentText(CloudPath.of("/knowledge/test.txt")))
        .thenReturn(
            new CloudTextRevision(
                doc.getId(), 1L, "hit 1\nhit 2\nhit 3\n", 20L, "h", true, Instant.now()));

    ToolResult result =
        execute(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"hit\",\"limit\":2}");

    assertFalse(result.error());
    String text = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(text.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
  }

  @Test
  void invalidRegexSyntaxDoesNotLeakPattern() {
    // 意图：非法 RE2 正则语法报错且不回显用户 pattern
    String badPattern = "(?=lookaround)";
    ToolResult result =
        execute(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"" + badPattern + "\"}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertFalse(error.contains("lookaround"));
    assertTrue(error.contains("Invalid regular expression pattern"));
  }

  @Test
  void validationErrorsAndSingleFileGrep() {
    // 意图：参数校验、单文件直接 grep、非文本节点禁止 grep
    assertTrue(executeWithRawArgs(grepTool, "{\"path\":\"/knowledge\"}").error());
    assertTrue(
        executeWithRawArgs(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"a\",\"limit\":0}")
            .error());
    assertTrue(
        executeWithRawArgs(grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"a\",\"limit\":200000}")
            .error());
    assertTrue(
        executeWithRawArgs(
                grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"a\",\"timeout_seconds\":0}")
            .error());
    assertTrue(
        executeWithRawArgs(
                grepTool, "{\"path\":\"/knowledge\",\"pattern\":\"a\",\"timeout_seconds\":5000}")
            .error());
    // 严格类型校验
    assertTrue(executeWithRawArgs(grepTool, "{\"path\":123,\"pattern\":\"a\"}").error());
    assertTrue(executeWithRawArgs(grepTool, "{\"path\":\"/k\",\"pattern\":123}").error());
    assertTrue(
        executeWithRawArgs(grepTool, "{\"path\":\"/k\",\"pattern\":\"a\",\"limit\":\"10\"}")
            .error());
    assertTrue(
        executeWithRawArgs(grepTool, "{\"path\":\"/k\",\"pattern\":\"a\",\"literal\":\"true\"}")
            .error());
    assertTrue(
        executeWithRawArgs(grepTool, "{\"path\":\"/k\",\"pattern\":\"a\",\"ignore_case\":1}")
            .error());
    assertTrue(executeWithRawArgs(grepTool, "malformed_grep_json").error());

    CloudPath textFile = CloudPath.of("/file.txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "file.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(textFile)).thenReturn(textNode);
    when(mockService.readCurrentText(textFile))
        .thenReturn(
            new CloudTextRevision(
                textNode.getId(), 1L, "hello single file\n", 18L, "h", true, Instant.now()));

    ToolResult singleResult =
        execute(grepTool, "{\"path\":\"/file.txt\",\"pattern\":\"single\",\"timeout_seconds\":5}");
    assertFalse(singleResult.error());
    assertTrue(
        ((TextResultContent) singleResult.contents().get(0))
            .text()
            .contains("/file.txt:1:hello single file"));

    CloudPath blobFile = CloudPath.of("/image.png");
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "image.png",
            CloudNodeKind.BLOB,
            1L,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());
    when(mockService.getNode(blobFile)).thenReturn(blobNode);
    assertTrue(execute(grepTool, "{\"path\":\"/image.png\",\"pattern\":\"single\"}").error());
  }

  @Test
  void rootSearchWithIncludeAndArtifactErrorBranches() throws InterruptedException {
    // 意图：根目录 / 搜索、include 过滤、以及 artifact 的各种异常分支
    CloudPath root = CloudPath.of("/");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode subDoc =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "root_doc.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode ignoredDoc =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "root_doc.log",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(root)).thenReturn(rootNode);
    when(mockService.listChildren(root)).thenReturn(List.of(subDoc, ignoredDoc));
    when(mockService.readCurrentText(CloudPath.of("/root_doc.txt")))
        .thenReturn(
            new CloudTextRevision(
                subDoc.getId(), 1L, "root match\n", 11L, "h", true, Instant.now()));

    ToolResult rootGrep =
        execute(
            grepTool,
            "{\"path\":\"/\",\"pattern\":\"match\",\"include\":\"*.txt\",\"literal\":true,\"ignore_case\":true}");
    assertFalse(rootGrep.error());
    assertTrue(
        ((TextResultContent) rootGrep.contents().get(0))
            .text()
            .contains("/root_doc.txt:1:root match"));

    // Artifact 异常分支
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    CloudNode notBlob =
        new CloudNode(
            UUID.randomUUID(),
            null,
            invocationId + ".txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(artPath)).thenReturn(notBlob);
    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "c1",
            grepTool.descriptor().name(),
            "{\"path\":\"" + artPath.value() + "\",\"pattern\":\"abc\"}");
    ToolExecutionRequest req =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ofSeconds(10));
    grepTool.execute(req, listener);
    assertTrue(listener.await(Duration.ofSeconds(5)));
    assertNotNull(listener.error);

    // 运行时异常走 onError
    CaptureListener crashListener = new CaptureListener();
    ToolCall crashCall =
        new ToolCall(
            "c2", grepTool.descriptor().name(), "{\"path\":\"/crash\",\"pattern\":\"abc\"}");
    when(mockService.getNode(CloudPath.of("/crash")))
        .thenThrow(new RuntimeException("fatal crash"));
    grepTool.execute(
        new ToolExecutionRequest(grepTool.descriptor(), crashCall, Duration.ofSeconds(10)),
        crashListener);
    assertTrue(crashListener.await(Duration.ofSeconds(5)));
    assertNotNull(crashListener.error);
  }

  @Test
  void grepHandlesSearchTimeoutWithoutExplicitCancelProducesStableErrorResult() {
    // 意图：当搜索超时触发且未显式取消时，产生稳定的 ToolResult 错误结果
    CloudPath textPath = CloudPath.of("/timeout_grep.txt");
    CloudNode textNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "timeout_grep.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(textPath)).thenReturn(textNode);
    when(mockService.readCurrentText(textPath))
        .thenReturn(
            new CloudTextRevision(
                textNode.getId(), 1L, "line 1\nline 2\n", 14L, "hash", true, Instant.now()));

    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-timeout",
            grepTool.descriptor().name(),
            "{\"path\":\"/timeout_grep.txt\",\"pattern\":\"abc\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ofNanos(1));

    grepTool.execute(request, listener);
    try {
      assertTrue(listener.await(Duration.ofSeconds(5)), "Execution must complete within timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while awaiting tool execution");
    }

    assertNotNull(listener.outcome, "listener outcome must be called on timeout");
    assertTrue(listener.outcome.result().error());
    String out = ((TextResultContent) listener.outcome.result().contents().get(0)).text();
    assertTrue(out.contains("Search operation timed out"));
  }

  @Test
  void cancelInProgressSuppressesAllCallbacksAndStopsScanning() throws InterruptedException {
    // 意图：验证在扫描进行中调用 handle.cancel()：
    // 1. 原子标记取消并中断 worker 线程；
    // 2. 搜索扫描立即停止（后续文件不被读取）；
    // 3. 绝不向 listener 泄漏任何完成/错误回调。
    CloudPath rootPath = CloudPath.of("/cancel_dir");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "cancel_dir",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode f1 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "f1.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode f2 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "f2.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(f1, f2));

    CountDownLatch inScanning = new CountDownLatch(1);
    CountDownLatch cancelInvoked = new CountDownLatch(1);

    when(mockService.readCurrentText(CloudPath.of("/cancel_dir/f1.txt")))
        .thenAnswer(
            inv -> {
              inScanning.countDown();
              cancelInvoked.await(5, TimeUnit.SECONDS);
              return new CloudTextRevision(
                  f1.getId(), 1L, "line 1\n", 7L, "h", true, Instant.now());
            });

    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-cancel",
            grepTool.descriptor().name(),
            "{\"path\":\"/cancel_dir\",\"pattern\":\"target\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ofSeconds(10));

    ToolExecutionHandle handle = grepTool.execute(request, listener);
    assertFalse(handle.isCancelled());

    assertTrue(inScanning.await(5, TimeUnit.SECONDS), "Worker must enter scanning");

    handle.cancel();
    assertTrue(handle.isCancelled());
    cancelInvoked.countDown();

    // 验证后续 f2.txt 从未被读取
    verify(mockService, never()).readCurrentText(CloudPath.of("/cancel_dir/f2.txt"));

    boolean leaked = listener.latch.await(200, TimeUnit.MILLISECONDS);
    assertFalse(
        leaked, "Listener must NOT receive any terminal callback after explicit cancellation");
    assertNull(listener.outcome);
    assertNull(listener.error);
  }

  @Test
  void grepArtifactWithBinaryContentReturnsError() {
    // 意图：对非 UTF-8 文本 artifact 进行 grep 时返回明确错误结果
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    CloudPath artPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    UUID blobId = UUID.randomUUID();
    CloudNode blobNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            invocationId + ".txt",
            CloudNodeKind.BLOB,
            1L,
            blobId,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(artPath)).thenReturn(blobNode);
    when(mockBlobReader.read(blobId))
        .thenReturn(
            new BlobReadResult.Binary(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png", 4L));

    ToolResult result =
        execute(
            grepTool,
            "{\"path\":\"" + artPath.value() + "\",\"pattern\":\"PNG\",\"literal\":true}");
    assertTrue(result.error());
    String out = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(out.contains("Tool artifact is not valid UTF-8 text"));
  }

  @Test
  void grepDirectoryWalkRecoversEarlyOnLimitHit() {
    // 意图：目录树深度优先遍历时，在达到 limit + 1 预算后立即终止遍历并输出上限提示
    CloudPath rootPath = CloudPath.of("/tree");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "tree",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode subDir =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "subdir",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudPath subDirPath = CloudPath.of("/tree/subdir");
    CloudNode file1 =
        new CloudNode(
            UUID.randomUUID(),
            subDir.getId(),
            "f1.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudPath f1Path = CloudPath.of("/tree/subdir/f1.txt");

    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(subDir));
    when(mockService.listChildren(subDirPath)).thenReturn(List.of(file1));
    when(mockService.readCurrentText(f1Path))
        .thenReturn(
            new CloudTextRevision(
                file1.getId(), 1L, "hit1\nhit2\nhit3\n", 15L, "h", true, Instant.now()));

    ToolResult result =
        execute(grepTool, "{\"path\":\"/tree\",\"pattern\":\"hit\",\"literal\":true,\"limit\":2}");
    assertFalse(result.error());
    String out = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(out.contains("/tree/subdir/f1.txt:1:hit1"));
    assertTrue(out.contains("/tree/subdir/f1.txt:2:hit2"));
    assertTrue(out.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
  }

  @Test
  void cancelWaitsForInFlightCompletionCallbackAndSuppressesSubsequentCallbacks() throws Exception {
    // 意图：验证当完成回调已在执行时，cancel() 会等待其返回，且 cancel() 返回后绝无后续回调触发
    CloudPath docPath = CloudPath.of("/race.txt");
    CloudNode docNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "race.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(docPath)).thenReturn(docNode);
    when(mockService.readCurrentText(docPath))
        .thenReturn(
            new CloudTextRevision(
                docNode.getId(), 1L, "match line\n", 11L, "h", true, Instant.now()));

    CountDownLatch onCompleteEntered = new CountDownLatch(1);
    CountDownLatch allowOnCompleteToFinish = new CountDownLatch(1);
    AtomicBoolean cancelFinished = new AtomicBoolean(false);

    ToolExecutionListener blockingListener =
        new ToolExecutionListener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onComplete(ToolOutcome outcome) {
            onCompleteEntered.countDown();
            try {
              allowOnCompleteToFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          @Override
          public void onError(Throwable error) {}
        };

    ToolCall call =
        new ToolCall(
            "call-race-grep",
            grepTool.descriptor().name(),
            "{\"path\":\"/race.txt\",\"pattern\":\"match\",\"literal\":true}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ofSeconds(10));

    ToolExecutionHandle handle = grepTool.execute(request, blockingListener);
    assertTrue(onCompleteEntered.await(5, TimeUnit.SECONDS), "Worker must enter onComplete");

    Thread cancelThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  handle.cancel();
                  cancelFinished.set(true);
                });

    // 确认 cancelThread 在 allowOnCompleteToFinish 释放前等待 monitor
    assertFalse(
        cancelFinished.get(), "cancel() must wait for in-flight completion callback to return");

    allowOnCompleteToFinish.countDown();
    cancelThread.join(5000);
    assertTrue(cancelFinished.get(), "cancel() must return after completion callback finishes");
    assertFalse(handle.isCancelled());
  }

  @Test
  void effectiveTimeoutFallbackAndTightExplicitTimeout() {
    // 意图：验证 Duration.ZERO 请求降级到 descriptor 默认 1 小时，以及更紧请求超时生效
    assertEquals(Duration.ofHours(1), grepTool.descriptor().timeout());

    ToolCall call =
        new ToolCall(
            "call-eff-grep", grepTool.descriptor().name(), "{\"path\":\"/\",\"pattern\":\"abc\"}");
    ToolExecutionRequest zeroTimeoutReq =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ZERO);
    assertEquals(Duration.ofHours(1), zeroTimeoutReq.effectiveTimeout());

    ToolExecutionRequest tightTimeoutReq =
        new ToolExecutionRequest(grepTool.descriptor(), call, Duration.ofSeconds(2));
    assertEquals(Duration.ofSeconds(2), tightTimeoutReq.effectiveTimeout());
  }

  private static ToolResult executeWithRawArgs(CloudGrepTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = mock(ToolCall.class);
    when(call.id()).thenReturn("call-raw");
    when(call.argumentsJson()).thenReturn(argsJson);
    ToolExecutionRequest request = mock(ToolExecutionRequest.class);
    when(request.call()).thenReturn(call);
    tool.execute(request, listener);
    try {
      assertTrue(listener.await(Duration.ofSeconds(5)), "Execution must complete within timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while awaiting tool execution");
    }
    if (listener.outcome == null && listener.error != null) {
      throw new RuntimeException("Unexpected tool execution error", listener.error);
    }
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static ToolResult execute(CloudGrepTool tool, String argsJson) {
    CaptureListener listener = new CaptureListener();
    ToolCall call = new ToolCall("call-test", tool.descriptor().name(), argsJson);
    ToolExecutionRequest request =
        new ToolExecutionRequest(tool.descriptor(), call, Duration.ofSeconds(10));
    tool.execute(request, listener);
    try {
      assertTrue(listener.await(Duration.ofSeconds(5)), "Execution must complete within timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while awaiting tool execution");
    }
    if (listener.outcome == null && listener.error != null) {
      throw new RuntimeException("Unexpected tool execution error", listener.error);
    }
    assertNotNull(listener.outcome, "listener outcome must be called");
    return listener.outcome.result();
  }

  private static class CaptureListener implements ToolExecutionListener {
    final CountDownLatch latch = new CountDownLatch(1);
    volatile ToolOutcome outcome;
    volatile Throwable error;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome outcome) {
      this.outcome = outcome;
      latch.countDown();
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      latch.countDown();
    }

    boolean await(Duration timeout) throws InterruptedException {
      return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }
}
