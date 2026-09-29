package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Native;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * {@link WindowsJobScope} 中不依赖 kernel32 的部分：JNA 的字段映射与 Job 名派生。
 *
 * <p>Windows 的 Job 调用只能由 Windows runner 执行，但「结构体字段顺序/宽度是否与原生一致」是纯 Java 事实：一旦 JNA 映射漂移， kernel32
 * 就会读到错位的内存，而且只在 Windows 上表现为难以定位的失败。这里把它变成三个平台上都能跑的断言。
 */
class WindowsJobScopeTest {

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
}
