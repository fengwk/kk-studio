package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link WindowsJobScope} 的布局、失败去向与句柄所有权：前者是纯 Java 事实，后两者由包内可注入的 kernel 绑定驱动。
 *
 * <p>结构体字段顺序/宽度一旦与原生漂移，kernel32 就会读到错位内存，而且只在 Windows 上表现为难以定位的失败；失败去向与句柄 所有权同样不能在真实 Windows
 * 上靠观察进程表确认「有没有漏句柄」。因此这里把内核调用换成可核验的假实现，断言「每个已经拿到的 句柄在每种失败下都被交还，且只交还一次」——包括标准句柄校验与属性列表创建失败这类只在
 * Windows 上才会发生、却最难复现的路径。
 *
 * <p>真实失败（命令不存在、工作目录不存在、Job 名不存在）仍然由 Windows runner 用真实 kernel32 断言，假实现不替代它们。
 */
class WindowsJobScopeTest {

  private static final Path WORKDIR = Path.of(System.getProperty("java.io.tmpdir"));

  /** 结构体必须与 Windows 原生布局一致：{@code JOBOBJECT_BASIC_LIMIT_INFORMATION} 64 字节。 */
  @Test
  void basicLimitInformationMatchesTheNativeLayout() {
    assertEquals(64, new WindowsJobScope.JobBasicLimitInformation().size());
  }

  /** {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} = 基本限制 64 + IO_COUNTERS 48 + 四个内存上限 32。 */
  @Test
  void extendedLimitInformationMatchesTheNativeLayout() {
    assertEquals(144, new WindowsJobScope.JobExtendedLimitInformation().size());
  }

  /** {@code JOBOBJECT_BASIC_ACCOUNTING_INFORMATION}：收敛判定读的就是它的 ActiveProcesses。 */
  @Test
  void basicAccountingInformationMatchesTheNativeLayout() {
    assertEquals(48, new WindowsJobScope.JobBasicAccountingInformation().size());
  }

  /**
   * {@code STARTUPINFOEXW} = {@code STARTUPINFO} + 属性列表指针。
   *
   * <p>句柄列表与 Job 列表都通过这个结构交给内核，因此「属性列表指针紧跟 STARTUPINFO」必须与原生一致：x64 上 STARTUPINFO 为 104 字节（含结尾对齐），整体
   * 112 字节。
   */
  @Test
  void startupInfoExMatchesTheNativeLayout() {
    WindowsJobScope.STARTUPINFOEX startupInfoEx = new WindowsJobScope.STARTUPINFOEX();
    if (Native.POINTER_SIZE == 8) {
      assertEquals(104, startupInfoEx.StartupInfo.size(), "x64 上 STARTUPINFO 为 104 字节");
      assertEquals(112, startupInfoEx.size(), "加上属性列表指针后为 112 字节");
    }
    assertEquals(
        Native.POINTER_SIZE,
        startupInfoEx.size() - startupInfoEx.StartupInfo.size(),
        "属性列表指针必须紧跟 STARTUPINFO，中间不能夹入别的内容");
  }

  /** Job 名由调用私有的状态目录派生：同一个目录得到同一个名字，不同调用不会共用同一个 Job。 */
  @Test
  void jobNameIsDerivedFromTheStateDirectory() {
    Path first = Path.of("/tmp", ProcessScope.STATE_DIR_PREFIX + "1");
    Path second = Path.of("/tmp", ProcessScope.STATE_DIR_PREFIX + "2");
    String name = WindowsJobScope.jobNameFor(first);
    assertEquals(name, WindowsJobScope.jobNameFor(first), "同一个状态目录必须得到同一个 Job 名");
    assertNotEquals(name, WindowsJobScope.jobNameFor(second), "不同调用不能共用同一个 Job");
    assertTrue(name.matches("kk-studio-process-scope-[0-9a-f]{24}"), name);
  }

  /** 空命令在触碰内核之前就被拒绝：没有可执行文件的「范围」没有意义。 */
  @Test
  void emptyCommandIsRejectedBeforeTouchingTheKernel() {
    FakeKernel kernel = new FakeKernel();
    assertThrows(
        IllegalArgumentException.class,
        () -> WindowsJobScope.createSuspended(kernel, "job", List.of(), WORKDIR));
    assertEquals(List.of(), kernel.closedHandles());
  }

  /** Job 建立失败必须显式失败，并且此时还没有任何句柄需要交还。 */
  @Test
  void jobCreationFailureIsReportedWithoutLeaking() {
    FakeKernel kernel = new FakeKernel().withoutJob();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                WindowsJobScope.createSuspended(
                    kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR));
    assertTrue(
        failure.getMessage().contains("cannot create the scope job object"), failure.getMessage());
    assertEquals(List.of(), kernel.closedHandles());
  }

  /**
   * helper 的 stdout 句柄不可用时，已经建立的 stdin 管道两端必须全部交还。
   *
   * <p>这是句柄所有权的核心不变量：管道只属于这次调用，失败路径漏掉一端就会把句柄留在 helper JVM 里，直到整个进程结束——而 helper
   * 是长期运行的调用方进程派生出来的，泄漏会因为并发调用不断累积。
   */
  @Test
  void unavailableStandardOutputClosesThePipeHandles() {
    FakeKernel kernel = new FakeKernel().withoutStandardOutput();
    assertThrows(
        IllegalStateException.class,
        () ->
            WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR));
    assertEquals(List.of(FakeKernel.STDIN_READ, FakeKernel.STDIN_WRITE), kernel.closedHandles());
    assertTrue(kernel.isClosed(FakeKernel.JOB_HANDLE), "失败路径必须关掉已经建立的 Job 句柄");
  }

  /** 属性列表初始化失败同样必须交还管道两端与 Job 句柄。 */
  @Test
  void attributeListInitializationFailureClosesThePipeHandles() {
    FakeKernel kernel = new FakeKernel().withAttributeListInitialization(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR));
    assertEquals(List.of(FakeKernel.STDIN_READ, FakeKernel.STDIN_WRITE), kernel.closedHandles());
    assertTrue(kernel.isClosed(FakeKernel.JOB_HANDLE));
    assertEquals(0, kernel.deletedAttributeLists, "初始化失败时没有属性列表可删除");
  }

  /** 句柄列表属性写入失败：属性列表与管道都必须交还，标准句柄不属于我们，不能被关掉。 */
  @Test
  void handleListAttributeFailureClosesTheAttributeListAndPipes() {
    FakeKernel kernel = new FakeKernel().failingUpdateAttributeAt(1);
    assertThrows(
        IllegalStateException.class,
        () ->
            WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR));
    assertEquals(1, kernel.deletedAttributeLists);
    assertEquals(List.of(FakeKernel.STDIN_READ, FakeKernel.STDIN_WRITE), kernel.closedHandles());
    assertFalse(kernel.isClosed(FakeKernel.STDOUT_HANDLE), "helper 自己的标准句柄不归本次调用所有");
  }

  /** Job 列表属性写入失败：同样交还属性列表与管道。 */
  @Test
  void jobListAttributeFailureClosesTheAttributeListAndPipes() {
    FakeKernel kernel = new FakeKernel().failingUpdateAttributeAt(2);
    assertThrows(
        IllegalStateException.class,
        () ->
            WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR));
    assertEquals(1, kernel.deletedAttributeLists);
    assertEquals(List.of(FakeKernel.STDIN_READ, FakeKernel.STDIN_WRITE), kernel.closedHandles());
  }

  /** 命令无法启动时必须带上命令与工作目录，并且把所有已取得资源交还。 */
  @Test
  void commandStartFailureReportsTheCommandAndClosesEverything() {
    FakeKernel kernel = new FakeKernel().failingCreateProcess();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                WindowsJobScope.createSuspended(
                    kernel, "job", List.of("kk-studio-missing-exe"), WORKDIR));
    assertTrue(failure.getMessage().contains("kk-studio-missing-exe"), failure.getMessage());
    assertTrue(failure.getMessage().contains(WORKDIR.toString()), failure.getMessage());
    assertEquals(1, kernel.deletedAttributeLists);
    assertEquals(List.of(FakeKernel.STDIN_READ, FakeKernel.STDIN_WRITE), kernel.closedHandles());
    assertTrue(kernel.isClosed(FakeKernel.JOB_HANDLE));
  }

  /** 已经可继承的标准句柄不做多余改写：helper 自己的 stdout 属于它自己，这里既不能关也不该改。 */
  @Test
  void alreadyInheritableStandardHandleIsLeftAlone() {
    FakeKernel kernel = new FakeKernel().withInheritableStandardOutput();
    WindowsJobScope scope =
        WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    scope.close();
    assertEquals(0, kernel.standardHandleRewriteCount);
    assertFalse(kernel.isClosed(FakeKernel.STDOUT_HANDLE));
  }

  /** 成功路径：句柄所有权、恢复执行、退出码与整组收敛证据都必须成立。 */
  @Test
  void successfulScopeOwnsItsHandlesAndReportsTheNativeFacts() {
    FakeKernel kernel = new FakeKernel().withExitCode(7);
    WindowsJobScope scope =
        WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit 7"), WORKDIR);
    assertEquals(FakeKernel.PROCESS_HANDLE_PID, scope.processId());
    scope.resume();
    assertEquals(1, kernel.resumedThreads);
    assertEquals(7, scope.awaitExit());
    assertTrue(scope.awaitEmpty(50), "Job 报告没有活动进程即收敛");
    assertTrue(scope.terminateAndDrain(50));
    assertEquals(1, kernel.terminatedJobs);
    scope.close();
    scope.close();
    assertEquals(1, kernel.closeCount(FakeKernel.JOB_HANDLE), "重复 close 只能交还一次 Job 句柄");
    assertEquals(1, kernel.closeCount(FakeKernel.PROCESS_HANDLE));
    assertEquals(1, kernel.closeCount(FakeKernel.THREAD_HANDLE));
  }

  /** 从未恢复执行的挂起进程在失败关闭时必须由 helper 自己结束，而不是留给句柄语义。 */
  @Test
  void closeTerminatesANeverResumedSuspendedProcess() {
    FakeKernel kernel = new FakeKernel();
    WindowsJobScope scope =
        WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    scope.close();
    assertEquals(1, kernel.terminatedProcesses);
    assertTrue(kernel.isClosed(FakeKernel.PROCESS_HANDLE));
  }

  /** 查询整组是否结束的调用失败时必须按「还没有结束」处理，绝不能据此宣布收敛。 */
  @Test
  void drainFailsClosedWhenTheJobQueryFails() {
    FakeKernel kernel = new FakeKernel().failingJobQuery();
    WindowsJobScope scope =
        WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    assertFalse(scope.awaitEmpty(20));
    assertFalse(scope.terminateAndDrain(20));
    scope.close();
  }

  /** 等待进程退出与读取退出码的失败都必须显式失败，而不是返回一个假退出码。 */
  @Test
  void awaitExitReportsWaitAndExitCodeFailures() {
    FakeKernel waiting = new FakeKernel().failingWait();
    WindowsJobScope first =
        WindowsJobScope.createSuspended(waiting, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    assertThrows(IllegalStateException.class, first::awaitExit);

    FakeKernel unreadable = new FakeKernel().failingExitCodeRead();
    WindowsJobScope second =
        WindowsJobScope.createSuspended(unreadable, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    assertThrows(IllegalStateException.class, second::awaitExit);
  }

  /** 恢复执行失败必须显式失败：命令不能停在挂起状态被当成「正在运行」。 */
  @Test
  void resumeFailureIsReported() {
    FakeKernel kernel = new FakeKernel().failingResume();
    WindowsJobScope scope =
        WindowsJobScope.createSuspended(kernel, "job", List.of("cmd", "/c", "exit"), WORKDIR);
    assertThrows(IllegalStateException.class, scope::resume);
    scope.close();
  }

  /** 父进程侧打开命名 Job：出现即接管，只有 Job 句柄归自己所有。 */
  @Test
  void attachSucceedsAndOwnsOnlyTheJobHandle() {
    FakeKernel kernel = new FakeKernel();
    WindowsJobScope scope = WindowsJobScope.attach(kernel, "job");
    assertThrows(IllegalStateException.class, scope::resume);
    scope.close();
    assertTrue(kernel.isClosed(FakeKernel.JOB_HANDLE));
  }

  /** Job 一直不出现时必须在预算内显式失败，而不是无限重试。 */
  @Test
  void attachFailsAfterTheBudgetWhenTheJobNeverAppears() {
    FakeKernel kernel = new FakeKernel().withoutJob();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> WindowsJobScope.attach(kernel, "missing-job"));
    assertTrue(failure.getMessage().contains("missing-job"), failure.getMessage());
    assertTrue(kernel.openAttempts > 1, "预算内必须重试，而不是一次就放弃");
  }

  /**
   * 真实 kernel32 上的失败去向：命令不存在、工作目录不存在、Job 名不存在。
   *
   * <p>这些只有真实 Windows 能构造，因此它们由 Windows runner 断言；假实现覆盖的是句柄所有权，不替代真实 API 的错误语义。
   */
  @Test
  void realKernelReportsMissingExecutableWorkdirAndJobName() {
    assumeTrue(isWindows(), "需要真实 kernel32 才能构造这些失败");
    String jobName = WindowsJobScope.jobNameFor(WORKDIR.resolve("missing-job"));
    IllegalStateException missingExecutable =
        assertThrows(
            IllegalStateException.class,
            () ->
                WindowsJobScope.createSuspended(
                    jobName, List.of("kk-studio-missing-exe"), WORKDIR));
    assertTrue(missingExecutable.getMessage().contains("kk-studio-missing-exe"));
    IllegalStateException missingWorkdir =
        assertThrows(
            IllegalStateException.class,
            () ->
                WindowsJobScope.createSuspended(
                    jobName, List.of("cmd", "/c", "exit"), WORKDIR.resolve("missing-workdir")));
    assertTrue(missingWorkdir.getMessage().contains("missing-workdir"));
    assertThrows(IllegalStateException.class, () -> WindowsJobScope.attach(jobName));
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * 可核验的假 kernel：只实现 {@link WindowsJobScope} 用到的调用，记录每一次句柄交还与属性列表释放。
   *
   * <p>它的价值不是「模拟 Windows」，而是把「已经取得的资源是否全部交还、是否只交还一次」变成可断言的事实；默认行为是 「一切成功」，需要失败打开哪个开关即可。
   */
  private static final class FakeKernel implements WindowsJobScope.WindowsKernel {

    static final long JOB_HANDLE = 301;
    static final long STDIN_READ = 101;
    static final long STDIN_WRITE = 102;
    static final long STDOUT_HANDLE = 201;
    static final long PROCESS_HANDLE = 401;
    static final long THREAD_HANDLE = 402;
    static final long PROCESS_HANDLE_PID = 4321;

    private final Map<Long, Integer> closeCounts = new LinkedHashMap<>();
    private boolean jobAvailable = true;
    private boolean standardOutputAvailable = true;
    private boolean standardOutputInheritable;
    private boolean attributeListInitialization = true;
    private int failingUpdateAttributeCall = -1;
    private int updateAttributeCalls;
    private boolean createProcess = true;
    private boolean jobQuery = true;
    private boolean wait = true;
    private boolean exitCodeRead = true;
    private int exitCode;
    private boolean resume = true;

    int deletedAttributeLists;
    int standardHandleRewriteCount;
    int resumedThreads;
    int terminatedJobs;
    int terminatedProcesses;
    int openAttempts;

    FakeKernel withoutJob() {
      jobAvailable = false;
      return this;
    }

    FakeKernel withoutStandardOutput() {
      standardOutputAvailable = false;
      return this;
    }

    FakeKernel withInheritableStandardOutput() {
      standardOutputInheritable = true;
      return this;
    }

    FakeKernel withAttributeListInitialization(boolean value) {
      attributeListInitialization = value;
      return this;
    }

    FakeKernel failingUpdateAttributeAt(int call) {
      failingUpdateAttributeCall = call;
      return this;
    }

    FakeKernel failingCreateProcess() {
      createProcess = false;
      return this;
    }

    FakeKernel failingJobQuery() {
      jobQuery = false;
      return this;
    }

    FakeKernel failingWait() {
      wait = false;
      return this;
    }

    FakeKernel failingExitCodeRead() {
      exitCodeRead = false;
      return this;
    }

    FakeKernel failingResume() {
      resume = false;
      return this;
    }

    FakeKernel withExitCode(int value) {
      exitCode = value;
      return this;
    }

    boolean isClosed(long handle) {
      return closeCounts.containsKey(handle);
    }

    int closeCount(long handle) {
      return closeCounts.getOrDefault(handle, 0);
    }

    /** 按交还顺序列出被关闭的句柄；只包含本次调用实际取得（或明确拥有）的句柄。 */
    List<Long> closedHandles() {
      List<Long> handles = new ArrayList<>();
      for (Map.Entry<Long, Integer> entry : closeCounts.entrySet()) {
        for (int index = 0; index < entry.getValue(); index++) {
          handles.add(entry.getKey());
        }
      }
      handles.remove(Long.valueOf(JOB_HANDLE));
      return handles;
    }

    @Override
    public HANDLE GetStdHandle(int stdHandle) {
      if (stdHandle == -11 && standardOutputAvailable) {
        return handle(STDOUT_HANDLE);
      }
      return null;
    }

    @Override
    public boolean GetHandleInformation(HANDLE handle, IntByReference flags) {
      // 默认当下不可继承：这样「让标准句柄可继承」这条路径也会被真正走到。
      flags.setValue(value(handle) == STDOUT_HANDLE && standardOutputInheritable ? 1 : 0);
      return true;
    }

    @Override
    public boolean SetHandleInformation(HANDLE handle, int mask, int flags) {
      if (value(handle) == STDOUT_HANDLE) {
        standardHandleRewriteCount++;
      }
      return true;
    }

    @Override
    public HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES jobAttributes, String name) {
      return jobAvailable ? handle(JOB_HANDLE) : null;
    }

    @Override
    public HANDLE OpenJobObject(int desiredAccess, boolean inheritHandle, String name) {
      openAttempts++;
      return jobAvailable ? handle(JOB_HANDLE) : null;
    }

    @Override
    public boolean SetInformationJobObject(
        HANDLE job, int informationClass, Pointer information, int size) {
      return true;
    }

    @Override
    public boolean QueryInformationJobObject(
        HANDLE job,
        int informationClass,
        Pointer information,
        int length,
        IntByReference returnedLength) {
      // 缓冲区初始为零：查询成功即「没有活动进程」，这正是收敛判定要读的事实。
      return jobQuery;
    }

    @Override
    public boolean CreateProcessW(
        String applicationName,
        String commandLine,
        WinBase.SECURITY_ATTRIBUTES processAttributes,
        WinBase.SECURITY_ATTRIBUTES threadAttributes,
        boolean inheritHandles,
        DWORD creationFlags,
        Pointer environment,
        String currentDirectory,
        Pointer startupInfo,
        WinBase.PROCESS_INFORMATION processInformation) {
      if (!createProcess) {
        return false;
      }
      processInformation.hProcess = handle(PROCESS_HANDLE);
      processInformation.hThread = handle(THREAD_HANDLE);
      processInformation.dwProcessId = new DWORD(PROCESS_HANDLE_PID);
      processInformation.dwThreadId = new DWORD(1);
      return true;
    }

    @Override
    public boolean CreatePipe(
        HANDLEByReference readPipe,
        HANDLEByReference writePipe,
        WinBase.SECURITY_ATTRIBUTES pipeAttributes,
        int size) {
      readPipe.setValue(handle(STDIN_READ));
      writePipe.setValue(handle(STDIN_WRITE));
      return true;
    }

    @Override
    public boolean InitializeProcThreadAttributeList(
        Pointer attributeList, int attributeCount, int flags, Pointer size) {
      return attributeListInitialization;
    }

    @Override
    public boolean UpdateProcThreadAttribute(
        Pointer attributeList,
        int flags,
        Pointer attribute,
        Pointer value,
        SIZE_T size,
        Pointer previousValue,
        Pointer returnSize) {
      updateAttributeCalls++;
      return failingUpdateAttributeCall != updateAttributeCalls;
    }

    @Override
    public void DeleteProcThreadAttributeList(Pointer attributeList) {
      deletedAttributeLists++;
    }

    @Override
    public int ResumeThread(HANDLE thread) {
      if (!resume) {
        return -1;
      }
      resumedThreads++;
      return 1;
    }

    @Override
    public int WaitForSingleObject(HANDLE handle, int milliseconds) {
      return wait ? 0 : 1;
    }

    @Override
    public boolean GetExitCodeProcess(HANDLE process, IntByReference exitCodeReference) {
      exitCodeReference.setValue(exitCode);
      return exitCodeRead;
    }

    @Override
    public boolean TerminateProcess(HANDLE process, int exitCode) {
      terminatedProcesses++;
      return true;
    }

    @Override
    public boolean TerminateJobObject(HANDLE job, int exitCode) {
      terminatedJobs++;
      return true;
    }

    @Override
    public boolean CloseHandle(HANDLE handle) {
      closeCounts.merge(value(handle), 1, Integer::sum);
      return true;
    }

    private static HANDLE handle(long value) {
      Pointer pointer = new Memory(Native.POINTER_SIZE);
      pointer.setPointer(0, Pointer.createConstant(value));
      return new HANDLE(pointer);
    }

    private static long value(HANDLE handle) {
      return Pointer.nativeValue(handle.getPointer().getPointer(0));
    }
  }
}
