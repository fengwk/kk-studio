package fun.fengwk.kkstudio.harness.daemon.terminal;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.TerminalSelection;

/**
 * 显式 headless {@link TerminalDisplay}：只记录内核投影需要的显示事实。
 *
 * <p>该实现直接实现接口，不使用动态 {@code Proxy}、私有反射或 GUI。光标位置与权威尺寸以内核持有的 {@code JediTerminal}、备用缓冲以缓冲为准，
 * 因此这里只保留公开回调给出的显示态（窗口标题、光标形状与可见性、鼠标模式/格式）。
 */
final class HeadlessTerminalDisplay implements TerminalDisplay {

  private String windowTitle = "";
  private CursorShape cursorShape;
  private boolean cursorVisible = true;
  private MouseMode mouseMode = MouseMode.MOUSE_REPORTING_NONE;
  private MouseFormat mouseFormat = MouseFormat.MOUSE_FORMAT_XTERM;

  @Override
  public void setCursor(int x, int y) {
    // 光标位置以 JediTerminal 的权威坐标为准，Display 侧不重复记录。
  }

  @Override
  public void setCursorShape(CursorShape cursorShape) {
    this.cursorShape = cursorShape;
  }

  @Override
  public void beep() {
    // headless：无本地提示出口。
  }

  @Override
  public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
    // 视口滚动由 JediTerm 缓冲完成，headless Display 没有独立视口。
  }

  @Override
  public void setCursorVisible(boolean cursorVisible) {
    this.cursorVisible = cursorVisible;
  }

  @Override
  public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
    // 备用缓冲切换由 JediTerm 缓冲完成。
  }

  @Override
  public String getWindowTitle() {
    return windowTitle;
  }

  @Override
  public void setWindowTitle(String windowTitle) {
    this.windowTitle = windowTitle;
  }

  @Override
  public TerminalSelection getSelection() {
    return null;
  }

  @Override
  public void terminalMouseModeSet(MouseMode mouseMode) {
    this.mouseMode = mouseMode;
  }

  @Override
  public void setMouseFormat(MouseFormat mouseFormat) {
    this.mouseFormat = mouseFormat;
  }

  @Override
  public boolean ambiguousCharsAreDoubleWidth() {
    return false;
  }

  CursorShape cursorShape() {
    return cursorShape;
  }

  boolean cursorVisible() {
    return cursorVisible;
  }

  MouseMode mouseMode() {
    return mouseMode;
  }

  MouseFormat mouseFormat() {
    return mouseFormat;
  }
}
