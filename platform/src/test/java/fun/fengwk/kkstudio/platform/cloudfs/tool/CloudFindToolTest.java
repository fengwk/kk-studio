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
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 验证 {@link CloudFindTool} 的路径检索、目录后缀、跳过 Artifact 树与 limit+1 early stop 契约。 */
class CloudFindToolTest {

  private CloudFileSystemService mockService;
  private CloudFindTool findTool;

  @BeforeEach
  void setUp() {
    mockService = mock(CloudFileSystemService.class);
    findTool = new CloudFindTool(mockService);
  }

  @Test
  void findFilesAndDirectoriesWithTrailingSlash() {
    // 意图：匹配到的目录应带 '/' 后缀，且输出规范绝对路径
    CloudPath rootPath = CloudPath.of("/knowledge");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode subDir =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "projects",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode doc1 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "guide.md",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode doc2 =
        new CloudNode(
            UUID.randomUUID(),
            subDir.getId(),
            "arch.md",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(doc1, subDir));
    when(mockService.listChildren(CloudPath.of("/knowledge/projects"))).thenReturn(List.of(doc2));

    ToolResult result = execute(findTool, "{\"path\":\"/knowledge\",\"pattern\":\"**/*.md\"}");

    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(output.contains("/knowledge/guide.md"));
    assertTrue(output.contains("/knowledge/projects/arch.md"));
  }

  @Test
  void findStopsEarlyAtLimitPlusOne() {
    // 意图：当匹配数达到 limit + 1 时立即停止遍历，并在末尾附加限额说明
    CloudPath rootPath = CloudPath.of("/knowledge");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "knowledge",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode f1 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "a.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode f2 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "b.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode f3 =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "c.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(f1, f2, f3));

    ToolResult result =
        execute(findTool, "{\"path\":\"/knowledge\",\"pattern\":\"*.txt\",\"limit\":2}");

    assertFalse(result.error());
    String output = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(output.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
    assertTrue(output.contains("/knowledge/a.txt"));
    assertTrue(output.contains("/knowledge/b.txt"));
    assertFalse(output.contains("/knowledge/c.txt"));
  }

  @Test
  void searchingUnderArtifactsIsForbidden() {
    // 意图：直接在 /.artifacts 目录下执行 find 应被拒绝
    ToolResult result = execute(findTool, "{\"path\":\"/.artifacts\",\"pattern\":\"*\"}");

    assertTrue(result.error());
    String error = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(error.contains("forbidden"));
  }

  @Test
  void validationErrorsAndRootSearch() {
    // 意图：验证缺少 pattern、非法 limit/timeout、根目录非目录及 searchRoot 为 / 的遍历
    assertTrue(executeWithRawArgs(findTool, "{\"path\":\"/dir\"}").error());
    assertTrue(
        executeWithRawArgs(findTool, "{\"path\":\"/dir\",\"pattern\":\"*\",\"limit\":0}").error());
    assertTrue(
        executeWithRawArgs(findTool, "{\"path\":\"/dir\",\"pattern\":\"*\",\"limit\":200000}")
            .error());
    assertTrue(
        executeWithRawArgs(findTool, "{\"path\":\"/dir\",\"pattern\":\"*\",\"timeout_seconds\":0}")
            .error());
    assertTrue(
        executeWithRawArgs(
                findTool, "{\"path\":\"/dir\",\"pattern\":\"*\",\"timeout_seconds\":5000}")
            .error());
    // 严格类型校验
    assertTrue(executeWithRawArgs(findTool, "{\"path\":123,\"pattern\":\"*\"}").error());
    assertTrue(executeWithRawArgs(findTool, "{\"path\":\"/dir\",\"pattern\":123}").error());
    assertTrue(
        executeWithRawArgs(findTool, "{\"path\":\"/dir\",\"pattern\":\"*\",\"limit\":\"20\"}")
            .error());
    assertTrue(executeWithRawArgs(findTool, "malformed_find_json").error());

    CloudPath filePath = CloudPath.of("/file.txt");
    when(mockService.getNode(filePath))
        .thenReturn(
            new CloudNode(
                UUID.randomUUID(),
                null,
                "file.txt",
                CloudNodeKind.TEXT,
                1L,
                null,
                Instant.now(),
                Instant.now()));
    assertTrue(execute(findTool, "{\"path\":\"/file.txt\",\"pattern\":\"*\"}").error());

    // 搜索根为 /
    CloudPath rootPath = CloudPath.of("/");
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
    CloudNode topFile =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "root_file.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(topFile));

    ToolResult rootResult =
        execute(findTool, "{\"path\":\"/\",\"pattern\":\"*.txt\",\"timeout_seconds\":5}");
    assertFalse(rootResult.error());
    String rootOut = ((TextResultContent) rootResult.contents().get(0)).text();
    assertTrue(rootOut.contains("/root_file.txt"));
  }

  @Test
  void unexpectedExceptionTriggersOnError() throws InterruptedException {
    // 意图：非受控运行时异常触发 listener.onError
    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-err", findTool.descriptor().name(), "{\"path\":\"/error\",\"pattern\":\"*\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ofSeconds(10));
    when(mockService.getNode(CloudPath.of("/error")))
        .thenThrow(new RuntimeException("database crashed"));

    findTool.execute(request, listener);
    assertTrue(listener.await(Duration.ofSeconds(5)));
    assertNotNull(listener.error);
    assertTrue(listener.error.getMessage().contains("database crashed"));
  }

  @Test
  void findMatchesDirectoryWithTrailingSlashAndTerminatesEarlyOnRecursiveWalk() {
    // 意图：匹配目录节点时末尾添加 /，且在递归子树累积达到 limit + 1 预算时立即提前终止递归
    CloudPath rootPath = CloudPath.of("/project");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "project",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode docsDir =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "docs",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode subDir =
        new CloudNode(
            UUID.randomUUID(),
            docsDir.getId(),
            "sub",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    CloudNode fileA =
        new CloudNode(
            UUID.randomUUID(),
            docsDir.getId(),
            "a.txt",
            CloudNodeKind.TEXT,
            1L,
            null,
            Instant.now(),
            Instant.now());

    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of(docsDir));
    when(mockService.listChildren(CloudPath.of("/project/docs")))
        .thenReturn(List.of(subDir, fileA));

    // limit = 2，匹配到的目录项带有 /
    ToolResult result = execute(findTool, "{\"path\":\"/project\",\"pattern\":\"**\",\"limit\":2}");
    assertFalse(result.error());
    String out = ((TextResultContent) result.contents().get(0)).text();
    assertTrue(out.contains("/project/docs/"), "Matched directory must end with /");
    assertTrue(out.contains("[2 results limit reached. Refine the pattern or raise limit.]"));
  }

  @Test
  void findHandlesSearchTimeoutWithoutExplicitCancelProducesStableErrorResult() {
    // 意图：当搜索超时触发且未显式取消时，产生稳定的 ToolResult 错误结果
    CloudPath rootPath = CloudPath.of("/timeout_dir");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "timeout_dir",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(rootPath)).thenReturn(rootNode);

    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-timeout",
            findTool.descriptor().name(),
            "{\"path\":\"/timeout_dir\",\"pattern\":\"*\"}");
    // 请求级 timeout 为 1 纳秒，SearchControl 在启动后初次 check 即超时
    ToolExecutionRequest request =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ofNanos(1));

    findTool.execute(request, listener);
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
  void cancelInProgressSuppressesAllCallbacksAndStopsTraversal() throws InterruptedException {
    // 意图：验证在遍历进行中调用 handle.cancel()：
    // 1. 原子标记取消并中断 worker 线程；
    // 2. 遍历立即停止（后续目录不被访问）；
    // 3. 绝不向 listener 泄漏任何完成/错误回调（显式取消由网关管理生命周期）。
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
    CloudNode subDir =
        new CloudNode(
            UUID.randomUUID(),
            rootNode.getId(),
            "sub",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(rootPath)).thenReturn(rootNode);

    CountDownLatch inTraversal = new CountDownLatch(1);
    CountDownLatch cancelInvoked = new CountDownLatch(1);

    when(mockService.listChildren(rootPath))
        .thenAnswer(
            inv -> {
              inTraversal.countDown();
              cancelInvoked.await(5, TimeUnit.SECONDS);
              return List.of(subDir);
            });

    CaptureListener listener = new CaptureListener();
    ToolCall call =
        new ToolCall(
            "call-cancel",
            findTool.descriptor().name(),
            "{\"path\":\"/cancel_dir\",\"pattern\":\"*\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ofSeconds(10));

    ToolExecutionHandle handle = findTool.execute(request, listener);
    assertFalse(handle.isCancelled());

    // 等待确保 worker 虚拟线程已进入遍历阶段
    assertTrue(inTraversal.await(5, TimeUnit.SECONDS), "Worker must enter traversal");

    // 执行取消
    handle.cancel();
    assertTrue(handle.isCancelled());
    cancelInvoked.countDown();

    // 验证后续更深层次目录从未被访问
    verify(mockService, never()).listChildren(CloudPath.of("/cancel_dir/sub"));

    // 验证 listener 在取消后绝不泄漏任何终态回调
    boolean leaked = listener.latch.await(200, TimeUnit.MILLISECONDS);
    assertFalse(
        leaked, "Listener must NOT receive any terminal callback after explicit cancellation");
    assertNull(listener.outcome);
    assertNull(listener.error);
  }

  @Test
  void cancelWaitsForInFlightCompletionCallbackAndSuppressesSubsequentCallbacks() throws Exception {
    // 意图：验证当完成回调已在执行时，cancel() 会等待其返回，且 cancel() 返回后绝无后续回调触发
    CloudPath rootPath = CloudPath.of("/race_dir");
    CloudNode rootNode =
        new CloudNode(
            UUID.randomUUID(),
            null,
            "race_dir",
            CloudNodeKind.DIRECTORY,
            1L,
            null,
            Instant.now(),
            Instant.now());
    when(mockService.getNode(rootPath)).thenReturn(rootNode);
    when(mockService.listChildren(rootPath)).thenReturn(List.of());

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
            "call-race",
            findTool.descriptor().name(),
            "{\"path\":\"/race_dir\",\"pattern\":\"*\"}");
    ToolExecutionRequest request =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ofSeconds(10));

    ToolExecutionHandle handle = findTool.execute(request, blockingListener);
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
    assertEquals(Duration.ofHours(1), findTool.descriptor().timeout());

    ToolCall call =
        new ToolCall(
            "call-eff", findTool.descriptor().name(), "{\"path\":\"/\",\"pattern\":\"*\"}");
    ToolExecutionRequest zeroTimeoutReq =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ZERO);
    assertEquals(Duration.ofHours(1), zeroTimeoutReq.effectiveTimeout());

    ToolExecutionRequest tightTimeoutReq =
        new ToolExecutionRequest(findTool.descriptor(), call, Duration.ofSeconds(2));
    assertEquals(Duration.ofSeconds(2), tightTimeoutReq.effectiveTimeout());
  }

  private static ToolResult executeWithRawArgs(CloudFindTool tool, String argsJson) {
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

  private static ToolResult execute(CloudFindTool tool, String argsJson) {
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
