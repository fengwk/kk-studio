package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 启动失败收尾路径的确定性行为：用受控 {@link Process} 与私有的真实收尾方法构造「收敛」「未收敛」「终止抛错」三种结论。
 *
 * <p>这里的事实是控制流证据，不是 OS 物理证据：受控进程只回答 {@code destroyForcibly()}、{@code isAlive()} 与 {@code
 * waitFor(...)}，因此它证明的是「收尾结论如何被分类与交付」，真实进程的收敛由 {@link ProcessScopeCrossPlatformTest} 用原生 PID 取证。
 *
 * <p>覆盖的事实：清理已经证明收敛时普通启动失败保持原身份；清理没能证明收敛时普通失败被固定的 {@link ProcessScope.StartupCleanupFailure}
 * 承载，且这个边界能被 {@link LspClient} 按「自己的类型」与「被 startScope 包装一次的形态」识别；首个 {@link Error} 保持身份、后到的 Error
 * 提升到普通异常之上；终止失败时状态目录 仍然被删除；抑制被禁用的 Error 也不影响结论（结论绝不依赖 suppressed 元数据）。
 */
class ProcessScopeStartFailureTest {

  @TempDir Path scopeRoot;

  /** 清理已经证明收敛：普通启动失败保持原身份，状态目录仍然被删除。 */
  @Test
  void cleanStartupFailureKeepsTheOrdinaryFailureIdentity() throws Exception {
    Path stateDir = newStateDirectory();
    RuntimeException original = new ToolServiceFailureException("startup");
    ProcessScope scope = scope(stateDir, new ControlledProcess(false, null));

    RuntimeException failure = failStart(original, scope);

    assertSame(original, failure, "清理已经证明收敛时普通启动失败必须保持原身份");
    assertFalse(Files.exists(stateDir), "启动失败收尾必须删除调用私有的状态目录");
  }

  /** 终止返回 {@code false}：普通失败由固定边界承载，且该边界能被 LspClient 按两种已知形态识别。 */
  @Test
  void unprovenCleanupIsReportedAsTheTypedBoundary() throws Exception {
    Path stateDir = newStateDirectory();
    RuntimeException original = new ToolServiceFailureException("startup");
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, null));

    ProcessScope.StartupCleanupFailure failure =
        assertThrows(ProcessScope.StartupCleanupFailure.class, () -> failStart(original, scope));

    assertSame(original, failure.getCause(), "固定边界必须保留原始启动失败作为 cause");
    assertFalse(Files.exists(stateDir), "终止未证明收敛时仍然必须删除私有状态目录");
    assertTrue(
        LspClient.startupCleanupFailed(
            new ToolServiceFailureException("cannot be started", failure)),
        "被 startScope 包装一次的范围边界必须被识别");
    assertFalse(
        LspClient.startupCleanupFailed(new ToolServiceFailureException("cannot be started")),
        "没有清理边界的普通启动失败不得被误判为清理失败");
  }

  /** 本类自己的固定边界同样必须被识别：它只包住普通失败，因此用反射构造就是控制流证据。 */
  @Test
  void clientStartupCleanupBoundaryIsRecognizedByItsOwnType() throws Exception {
    Throwable boundary =
        newClientStartupCleanupBoundary(new ToolServiceFailureException("startup"));

    assertTrue(LspClient.startupCleanupFailed(boundary), "本类自己的固定边界必须被识别");
  }

  /** 两个 Error：首个 Error 保持身份，后到的 Error 只作为 suppressed 附着。 */
  @Test
  void firstErrorKeepsItsIdentityWhenCleanupAlsoFails() throws Exception {
    Path stateDir = newStateDirectory();
    Error original = new AssertionError("startup");
    Error terminationFailure = new AssertionError("termination");
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, terminationFailure));

    Error failure = assertThrows(AssertionError.class, () -> failStart(original, scope));

    assertSame(original, failure, "首个 Error 保持身份，未证明收敛不得把它包装成普通失败");
    assertEquals(List.of(terminationFailure), List.of(failure.getSuppressed()));
    assertFalse(Files.exists(stateDir), "终止失败之后仍然必须删除私有状态目录");
  }

  /** 清理抛出的 Error 提升到普通启动失败之上：Error 身份原样交付，普通失败作为 suppressed 保留。 */
  @Test
  void cleanupErrorOutranksTheOrdinaryFailure() throws Exception {
    Path stateDir = newStateDirectory();
    RuntimeException original = new ToolServiceFailureException("startup");
    Error terminationFailure = new AssertionError("termination");
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, terminationFailure));

    Error failure = assertThrows(AssertionError.class, () -> failStart(original, scope));

    assertSame(terminationFailure, failure, "清理 Error 必须提升到普通启动失败之上");
    assertEquals(List.of(original), List.of(failure.getSuppressed()));
    assertFalse(Files.exists(stateDir), "终止失败之后仍然必须删除私有状态目录");
  }

  /** 终止自己抛出时同样是「未证明收敛」：状态目录仍然被删除，原始失败仍然被固定边界承载。 */
  @Test
  void terminationThrowingIsAlsoUnprovenCleanup() throws Exception {
    Path stateDir = newStateDirectory();
    RuntimeException original = new ToolServiceFailureException("startup");
    RuntimeException terminationFailure = new IllegalStateException("termination");
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, terminationFailure));

    ProcessScope.StartupCleanupFailure failure =
        assertThrows(ProcessScope.StartupCleanupFailure.class, () -> failStart(original, scope));

    assertSame(original, failure.getCause(), "原始启动失败必须保留为 cause");
    assertEquals(
        List.of(terminationFailure), List.of(original.getSuppressed()), "终止失败作为 suppressed 附着");
    assertFalse(Files.exists(stateDir), "终止抛错之后仍然必须尝试删除状态目录");
  }

  /** 抑制被禁用的 Error：身份照旧保持，结论绝不依赖 suppressed 元数据。 */
  @Test
  void suppressionDisabledErrorsStillKeepTheirIdentity() throws Exception {
    Path stateDir = newStateDirectory();
    SuppressionDisabledError original = new SuppressionDisabledError("startup");
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, null));

    Error failure = assertThrows(SuppressionDisabledError.class, () -> failStart(original, scope));

    assertSame(original, failure, "抑制被禁用时 Error 身份同样必须保持");
    assertEquals(0, failure.getSuppressed().length, "抑制被禁用时不得留下任何 suppressed 元数据");
    assertFalse(LspClient.startupCleanupFailed(original), "Error 不依赖清理边界识别：池按 Error 身份把它当成终结事实");
    assertFalse(Files.exists(stateDir), "启动失败收尾必须删除调用私有的状态目录");

    Path otherDir = newStateDirectory();
    RuntimeException ordinary = new ToolServiceFailureException("startup");
    SuppressionDisabledError cleanupError = new SuppressionDisabledError("termination");
    Error promoted =
        assertThrows(
            SuppressionDisabledError.class,
            () -> failStart(ordinary, scope(otherDir, new ControlledProcess(true, cleanupError))));
    assertSame(cleanupError, promoted, "抑制被禁用的清理 Error 同样必须按身份提升");
    assertEquals(0, promoted.getSuppressed().length, "抑制被禁用时不得留下任何 suppressed 元数据");
  }

  /** 删除目录也可能失败；后到的 Error 不得替换已有的启动 Error。 */
  @Test
  void stateDirectoryFailureDoesNotMaskTheFirstError() throws Exception {
    Error original = new AssertionError("startup");
    Error deletion = new AssertionError("state directory cleanup");
    ProcessScope scope = scope(failingStateDirectory(deletion), new ControlledProcess(false, null));

    Error failure = assertThrows(AssertionError.class, () -> failStart(original, scope));

    assertSame(original, failure);
    assertEquals(List.of(deletion), List.of(failure.getSuppressed()));
  }

  /** 删除目录的致命失败必须提升到普通启动失败之上。 */
  @Test
  void stateDirectoryErrorOutranksAnOrdinaryStartFailure() throws Exception {
    RuntimeException original = new IllegalStateException("startup");
    Error deletion = new AssertionError("state directory cleanup");
    ProcessScope scope = scope(failingStateDirectory(deletion), new ControlledProcess(false, null));

    Error failure = assertThrows(AssertionError.class, () -> failStart(original, scope));

    assertSame(deletion, failure);
    assertEquals(List.of(original), List.of(failure.getSuppressed()));
  }

  /** 同一个 Error 被启动和终止路径共同抛出时不得自抑制。 */
  @Test
  void repeatedErrorIdentityDoesNotCauseSelfSuppression() throws Exception {
    Error original = new AssertionError("startup and termination");
    Path stateDir = newStateDirectory();
    ProcessScope scope = scope(stateDir, new ControlledProcess(true, original));

    assertSame(original, assertThrows(AssertionError.class, () -> failStart(original, scope)));
    assertEquals(0, original.getSuppressed().length);
    assertFalse(Files.exists(stateDir));
  }

  /** 已发布的终止结论优先于重复调用者的中断状态；成功与未证明收敛都不得被改写。 */
  @Test
  void interruptedRepeatCallerSeesThePublishedTerminationFact() throws Exception {
    for (boolean alive : new boolean[] {false, true}) {
      try (ProcessScope scope = scope(newStateDirectory(), new ControlledProcess(alive, null))) {
        assertEquals(!alive, scope.terminate());
        Thread.currentThread().interrupt();
        try {
          assertEquals(!alive, scope.terminate());
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  /** 在路径访问点制造确定的失败，不替换生产删除逻辑或创建真实 OS 资源。 */
  private static Path failingStateDirectory(Throwable failure) {
    return (Path)
        Proxy.newProxyInstance(
            Path.class.getClassLoader(),
            new Class<?>[] {Path.class},
            (proxy, method, args) -> {
              if ("getFileSystem".equals(method.getName())) {
                throw failure;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** 反射调用私有的失败收尾路径：它是生产代码本身，真实启动失败走的就是这里。 */
  private static RuntimeException failStart(Throwable original, ProcessScope scope)
      throws Exception {
    Method method =
        ProcessScope.class.getDeclaredMethod("failStart", Throwable.class, ProcessScope.class);
    method.setAccessible(true);
    try {
      return (RuntimeException) method.invoke(null, original, scope);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw (RuntimeException) cause;
    }
  }

  /** 反射构造私有的受控范围对象：状态目录由本次调用独占，因此删除断言总是针对本次调用。 */
  private static ProcessScope scope(Path stateDir, Process helper) throws Exception {
    Constructor<ProcessScope> constructor =
        ProcessScope.class.getDeclaredConstructor(Process.class, Path.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(helper, stateDir, false);
  }

  /** 反射构造 LspClient 私有的启动清理边界：它只包住普通失败，因此这里不产生任何真实资源。 */
  private static Throwable newClientStartupCleanupBoundary(RuntimeException cause)
      throws Exception {
    Class<?> type = Class.forName(LspClient.class.getName() + "$StartupCleanupFailure");
    Constructor<?> constructor = type.getDeclaredConstructor(RuntimeException.class);
    constructor.setAccessible(true);
    return (Throwable) constructor.newInstance(cause);
  }

  private Path newStateDirectory() throws IOException {
    return Files.createTempDirectory(scopeRoot, ProcessScope.STATE_DIR_PREFIX);
  }

  /**
   * 受控进程：收尾路径只依赖 {@code destroyForcibly()}、{@code isAlive()} 与 {@code waitFor(...)}，因此这里可以确定性地
   * 制造「已经退出」「仍然存活」与「终止抛错」三种结论，而不需要真实 OS 行为。
   */
  private static final class ControlledProcess extends Process {

    private final boolean alive;
    private final Throwable terminationFailure;

    private ControlledProcess(boolean alive, Throwable terminationFailure) {
      this.alive = alive;
      this.terminationFailure = terminationFailure;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exitValue();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return !alive;
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException("the controlled process is still alive");
      }
      return 0;
    }

    @Override
    public void destroy() {
      // 收敛路径只通过 destroyForcibly 表达终止动作。
    }

    @Override
    public Process destroyForcibly() {
      if (terminationFailure instanceof Error fatal) {
        throw fatal;
      }
      if (terminationFailure instanceof RuntimeException error) {
        throw error;
      }
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public long pid() {
      return 4242;
    }
  }

  /** 抑制被禁用的 Error：{@code addSuppressed} 无效，用来证明结论不依赖 suppressed 元数据。 */
  private static final class SuppressionDisabledError extends Error {

    private static final long serialVersionUID = 1L;

    private SuppressionDisabledError(String message) {
      super(message, null, false, true);
    }
  }
}
