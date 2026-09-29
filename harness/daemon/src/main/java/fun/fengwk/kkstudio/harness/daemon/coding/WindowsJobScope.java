package fun.fengwk.kkstudio.harness.daemon.coding;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.IO_COUNTERS;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Windows 执行范围：一个带 {@code KILL_ON_JOB_CLOSE} 的命名 Job 覆盖用户命令与它的全部后代。
 *
 * <p>helper 侧 {@link #createSuspended(String, List, Path)} 先建立 Job，再用 {@code CREATE_SUSPENDED}
 * 创建首个进程并 {@code AssignProcessToJobObject}，因此进程在归属完成之前不可能先派生出逃逸的后代；父进程侧 {@link #attach(String)}
 * 用同一个名字打开第二个句柄，从而在 helper 之外独立拥有「终止并查询整组」的能力。
 *
 * <p>收敛的唯一证据是 {@code QueryInformationJobObject(JobObjectBasicAccountingInformation).ActiveProcesses
 * == 0}： 首个进程的退出（哪怕它是唯一进程）都不等于整组已经结束。父进程只有在拿到这个证据之后才关闭自己的 Job 句柄，因此 「句柄关闭导致整组被杀」只是兜底，而不是收敛判定的依据。
 *
 * <p>stdin/stdout/stderr 通过 {@code STARTF_USESTDHANDLES} 继承 helper 的标准句柄，其中命令的 stderr 指向 helper 的
 * stdout，两路输出因此合并进同一条捕获管道；命令行按 {@link WindowsCommandLine} 的规则拼装；退出码由 {@code GetExitCodeProcess}
 * 原样带回。
 *
 * <p>本类只在 Windows 上初始化：JNA 接口按需加载 {@code kernel32}，非 Windows 平台不会触碰它。结构体与接口声明为 public 类型，避免 JNA
 * 反射访问时受包可见性限制。
 */
public final class WindowsJobScope {

  /** Job 名前缀：与调用私有的状态目录一一对应，且不会与其它并发调用相撞。 */
  private static final String JOB_NAME_PREFIX = "kk-studio-process-scope-";

  private static final int CREATE_SUSPENDED = 0x0000_0004;
  private static final int STARTF_USESTDHANDLES = 0x0000_0100;
  private static final int STD_INPUT_HANDLE = -10;
  private static final int STD_OUTPUT_HANDLE = -11;
  private static final int HANDLE_FLAG_INHERIT = 0x0000_0001;
  private static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS = 1;
  private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
  private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000;
  private static final int JOB_OBJECT_ALL_ACCESS = 0x001F_001F;
  private static final int INFINITE = 0xFFFF_FFFF;
  private static final int WAIT_OBJECT_0 = 0;

  /** 打开命名 Job 的重试预算：Job 由 helper 在发布范围之前建立，正常情况一次即可成功。 */
  private static final long ATTACH_BUDGET_MILLIS = 500;

  private static final long ATTACH_RETRY_INTERVAL_MILLIS = 10;
  private static final long POLL_INTERVAL_MILLIS = 5;

  private final HANDLE job;
  private final boolean owner;
  private HANDLE process;
  private HANDLE thread;
  private long processId;
  private boolean resumed;
  private boolean closed;

  private WindowsJobScope(HANDLE job, boolean owner) {
    this.job = job;
    this.owner = owner;
  }

  /** 从调用私有的状态目录派生 Job 名：父进程与 helper 对同一个目录得到同一个名字。 */
  static String jobNameFor(Path stateDir) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed =
          digest.digest(stateDir.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
      return JOB_NAME_PREFIX + HexFormat.of().formatHex(hashed, 0, 12);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required to derive the scope job name", error);
    }
  }

  /**
   * helper 侧：建立命名 Job 并以挂起状态创建首个进程，归属成功后才返回。
   *
   * <p>归属必须早于范围发布，这是父进程可以信赖的不变量：父进程一旦读到范围，就可以认定「要么没有任何命令进程，要么 它已经在 Job 内」，因此终止整组与「Job
   * 内没有活动进程」这两个结论都不可能漏掉命令进程。
   *
   * <p>任一步失败都释放已经拿到的句柄、结束从未运行过的挂起进程，再以带原因的异常失败关闭。
   *
   * <p>已知窗口：{@code CreateProcessW} 与 {@code AssignProcessToJobObject} 之间创建出来的进程还不属于 Job。若 helper 恰在
   * 这条指令间隙被强杀，它会留下一个从未执行过任何指令的挂起进程对象。要彻底消除这个窗口需要用 {@code STARTUPINFOEX} + {@code
   * PROC_THREAD_ATTRIBUTE_JOB_LIST} 让进程一创建就属于 Job，这属于另一层架构改动，
   * 不在本切片范围内；因此这里只保证「没有任何用户代码被执行」和「收敛判定绝不覆盖未归属进程」。
   */
  static WindowsJobScope createSuspended(String jobName, List<String> command, Path workdir) {
    if (command.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    WindowsKernel kernel = WindowsKernel.INSTANCE;
    HANDLE job = kernel.CreateJobObject(null, jobName);
    if (job == null) {
      throw new IllegalStateException("cannot create the scope job object: " + lastError());
    }
    WindowsJobScope scope = new WindowsJobScope(job, true);
    try {
      scope.limitJobLifetimeToHandle();
      WinBase.PROCESS_INFORMATION information = scope.createSuspendedProcess(command, workdir);
      scope.process = information.hProcess;
      scope.thread = information.hThread;
      scope.processId = information.dwProcessId.longValue();
      if (!kernel.AssignProcessToJobObject(job, scope.process)) {
        throw new IllegalStateException(
            "cannot assign the command process to the scope job object: " + lastError());
      }
      return scope;
    } catch (RuntimeException error) {
      scope.close();
      throw error;
    }
  }

  /**
   * 父进程侧：打开 helper 建立的命名 Job。
   *
   * <p>只有拿到这个句柄，父进程才能在 helper 之外终止整组并查询整组是否已经结束。
   */
  static WindowsJobScope attach(String jobName) {
    WindowsKernel kernel = WindowsKernel.INSTANCE;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ATTACH_BUDGET_MILLIS);
    while (true) {
      HANDLE job = kernel.OpenJobObject(JOB_OBJECT_ALL_ACCESS, false, jobName);
      if (job != null) {
        return new WindowsJobScope(job, false);
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "cannot open the scope job object " + jobName + ": " + lastError());
      }
      sleepQuietly(ATTACH_RETRY_INTERVAL_MILLIS);
    }
  }

  /** 首个进程的 pid；只用于诊断，收敛判定不依赖它。 */
  long processId() {
    return processId;
  }

  /** helper 侧：命令真正开始执行；调用方必须已经确认父进程持有 Job 句柄。 */
  void resume() {
    if (thread == null) {
      throw new IllegalStateException("the scope job object has no suspended command process");
    }
    if (WindowsKernel.INSTANCE.ResumeThread(thread) == -1) {
      throw new IllegalStateException("cannot resume the command process: " + lastError());
    }
    resumed = true;
  }

  /** helper 侧：等待首个进程退出并原样返回它自己的退出码。 */
  int awaitExit() {
    WindowsKernel kernel = WindowsKernel.INSTANCE;
    if (kernel.WaitForSingleObject(process, INFINITE) != WAIT_OBJECT_0) {
      throw new IllegalStateException("cannot wait for the command process: " + lastError());
    }
    IntByReference exitCode = new IntByReference();
    if (!kernel.GetExitCodeProcess(process, exitCode)) {
      throw new IllegalStateException("cannot read the command exit code: " + lastError());
    }
    return exitCode.getValue();
  }

  /**
   * 显式请求整组结束，并等到 Job 报告没有任何活动进程。
   *
   * <p>{@code TerminateJobObject} 的结果不参与判定：已经结束的 Job 会拒绝这个调用，而「整组是否结束」只能由 {@link #awaitEmpty(long)}
   * 的查询回答。
   */
  boolean terminateAndDrain(long budgetMillis) {
    WindowsKernel.INSTANCE.TerminateJobObject(job, 1);
    return awaitEmpty(budgetMillis);
  }

  /** 等到 Job 报告没有活动进程；查询失败按「还没有结束」处理。 */
  boolean awaitEmpty(long budgetMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    while (true) {
      Integer active = activeProcesses();
      if (active != null && active == 0) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
  }

  /** 释放本进程持有的 Job 句柄；helper 侧同时放弃它创建的挂起进程。 */
  void close() {
    if (closed) {
      return;
    }
    closed = true;
    WindowsKernel kernel = WindowsKernel.INSTANCE;
    if (owner && process != null) {
      if (!resumed) {
        // 从未运行过的挂起进程：helper 失败关闭时必须自己结束它，而不是把它留给句柄语义。
        kernel.TerminateProcess(process, 1);
      }
      kernel.CloseHandle(process);
    }
    if (owner && thread != null) {
      kernel.CloseHandle(thread);
    }
    kernel.CloseHandle(job);
  }

  private Integer activeProcesses() {
    JobBasicAccountingInformation accounting = new JobBasicAccountingInformation();
    IntByReference returned = new IntByReference();
    if (!WindowsKernel.INSTANCE.QueryInformationJobObject(
        job,
        JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS,
        accounting.getPointer(),
        accounting.size(),
        returned)) {
      return null;
    }
    accounting.read();
    return accounting.activeProcesses;
  }

  private void limitJobLifetimeToHandle() {
    JobExtendedLimitInformation limits = new JobExtendedLimitInformation();
    limits.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    limits.write();
    if (!WindowsKernel.INSTANCE.SetInformationJobObject(
        job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, limits.getPointer(), limits.size())) {
      throw new IllegalStateException("cannot limit the scope job object lifetime: " + lastError());
    }
  }

  /**
   * 以 suspended 状态创建进程，并把 helper 的标准句柄交给它。
   *
   * <p>命令的 stderr 与 stdout 指向同一个捕获句柄，两路输出因此合并；helper 自己的 stderr（诊断）不传给命令。句柄必须是
   * 可继承的，这里是显式校验/设置，而不是假定 Java 启动的进程一定给了可继承句柄。
   */
  private WinBase.PROCESS_INFORMATION createSuspendedProcess(List<String> command, Path workdir) {
    WindowsKernel kernel = WindowsKernel.INSTANCE;
    WinBase.STARTUPINFO startupInfo = new WinBase.STARTUPINFO();
    startupInfo.cb = new DWORD(startupInfo.size());
    startupInfo.dwFlags = STARTF_USESTDHANDLES;
    startupInfo.hStdInput = inheritableStandardHandle(kernel, STD_INPUT_HANDLE, "stdin");
    startupInfo.hStdOutput = inheritableStandardHandle(kernel, STD_OUTPUT_HANDLE, "stdout");
    startupInfo.hStdError = startupInfo.hStdOutput;
    WinBase.PROCESS_INFORMATION information = new WinBase.PROCESS_INFORMATION();
    boolean created =
        kernel.CreateProcess(
            applicationName(command.getFirst()),
            WindowsCommandLine.join(command),
            null,
            null,
            true,
            new DWORD(CREATE_SUSPENDED),
            null,
            workdir.toString(),
            startupInfo,
            information);
    if (!created) {
      throw new IllegalStateException(
          "cannot start command " + command.getFirst() + " in " + workdir + ": " + lastError());
    }
    return information;
  }

  /**
   * {@code lpApplicationName}：Windows 的语义是「给了名字就不用搜索路径，名字按当前目录解释」，因此裸可执行名会在这里被解释成
   * 「当前目录下的文件」。只有命令自带路径分量时才使用它，否则交给 {@code CreateProcess} 按标准顺序解析命令行首段。
   */
  private static String applicationName(String executable) {
    return executable.indexOf('\\') >= 0 || executable.indexOf('/') >= 0 ? executable : null;
  }

  private static HANDLE inheritableStandardHandle(
      WindowsKernel kernel, int stdHandle, String name) {
    HANDLE handle = kernel.GetStdHandle(stdHandle);
    if (handle == null || Pointer.nativeValue(handle.getPointer()) == -1L) {
      throw new IllegalStateException("the scope helper " + name + " handle is not available");
    }
    IntByReference flags = new IntByReference();
    if (!kernel.GetHandleInformation(handle, flags)) {
      throw new IllegalStateException(
          "cannot read the scope helper " + name + " handle flags: " + lastError());
    }
    if ((flags.getValue() & HANDLE_FLAG_INHERIT) == 0
        && !kernel.SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
      throw new IllegalStateException(
          "the scope helper " + name + " handle is not inheritable: " + lastError());
    }
    return handle;
  }

  private static String lastError() {
    return "GetLastError=" + Native.getLastError();
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  /** {@code JOBOBJECT_BASIC_LIMIT_INFORMATION} 的布局，只写入 {@code LimitFlags}。 */
  @FieldOrder({
    "perProcessUserTimeLimit",
    "perJobUserTimeLimit",
    "limitFlags",
    "minimumWorkingSetSize",
    "maximumWorkingSetSize",
    "activeProcessLimit",
    "affinity",
    "priorityClass",
    "schedulingClass"
  })
  public static final class JobBasicLimitInformation extends Structure {

    public long perProcessUserTimeLimit;
    public long perJobUserTimeLimit;
    public int limitFlags;
    public SIZE_T minimumWorkingSetSize;
    public SIZE_T maximumWorkingSetSize;
    public int activeProcessLimit;
    public ULONG_PTR affinity;
    public int priorityClass;
    public int schedulingClass;
  }

  /** {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}：把 KILL_ON_JOB_CLOSE 交给 Job。 */
  @FieldOrder({
    "basicLimitInformation",
    "ioInfo",
    "processMemoryLimit",
    "jobMemoryLimit",
    "peakProcessMemoryUsed",
    "peakJobMemoryUsed"
  })
  public static final class JobExtendedLimitInformation extends Structure {

    public JobBasicLimitInformation basicLimitInformation = new JobBasicLimitInformation();
    public IO_COUNTERS ioInfo = new IO_COUNTERS();
    public SIZE_T processMemoryLimit;
    public SIZE_T jobMemoryLimit;
    public SIZE_T peakProcessMemoryUsed;
    public SIZE_T peakJobMemoryUsed;
  }

  /** {@code JOBOBJECT_BASIC_ACCOUNTING_INFORMATION}：收敛判定只读它的 {@code ActiveProcesses}。 */
  @FieldOrder({
    "totalUserTime",
    "totalKernelTime",
    "thisPeriodTotalUserTime",
    "thisPeriodTotalKernelTime",
    "totalPageFaultCount",
    "totalProcesses",
    "activeProcesses",
    "totalTerminatedProcesses"
  })
  public static final class JobBasicAccountingInformation extends Structure {

    public long totalUserTime;
    public long totalKernelTime;
    public long thisPeriodTotalUserTime;
    public long thisPeriodTotalKernelTime;
    public int totalPageFaultCount;
    public int totalProcesses;
    public int activeProcesses;
    public int totalTerminatedProcesses;
  }

  /** kernel32 绑定；只在 Windows 上初始化，字符串参数统一按 Unicode（{@code *W}）解析。 */
  public interface WindowsKernel extends StdCallLibrary {

    WindowsKernel INSTANCE =
        Native.load("kernel32", WindowsKernel.class, W32APIOptions.UNICODE_OPTIONS);

    HANDLE GetStdHandle(int stdHandle);

    boolean GetHandleInformation(HANDLE handle, IntByReference flags);

    boolean SetHandleInformation(HANDLE handle, int mask, int flags);

    HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES jobAttributes, String name);

    HANDLE OpenJobObject(int desiredAccess, boolean inheritHandle, String name);

    boolean SetInformationJobObject(
        HANDLE job, int informationClass, Pointer information, int size);

    boolean QueryInformationJobObject(
        HANDLE job,
        int informationClass,
        Pointer information,
        int length,
        IntByReference returnedLength);

    boolean CreateProcess(
        String applicationName,
        String commandLine,
        WinBase.SECURITY_ATTRIBUTES processAttributes,
        WinBase.SECURITY_ATTRIBUTES threadAttributes,
        boolean inheritHandles,
        DWORD creationFlags,
        Pointer environment,
        String currentDirectory,
        WinBase.STARTUPINFO startupInfo,
        WinBase.PROCESS_INFORMATION processInformation);

    boolean AssignProcessToJobObject(HANDLE job, HANDLE process);

    int ResumeThread(HANDLE thread);

    int WaitForSingleObject(HANDLE handle, int milliseconds);

    boolean GetExitCodeProcess(HANDLE process, IntByReference exitCode);

    boolean TerminateProcess(HANDLE process, int exitCode);

    boolean TerminateJobObject(HANDLE job, int exitCode);

    boolean CloseHandle(HANDLE handle);
  }
}
