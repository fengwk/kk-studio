package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import org.junit.jupiter.api.Test;

/** 显式 headless Display 的记录与 headless 忽略行为。 */
class HeadlessTerminalDisplayTest {

  @Test
  void recordsDisplayStateAndIgnoresHeadlessOperations() {
    HeadlessTerminalDisplay display = new HeadlessTerminalDisplay();
    assertTrue(display.cursorVisible());
    assertNull(display.cursorShape());
    assertEquals(MouseMode.MOUSE_REPORTING_NONE, display.mouseMode());
    assertEquals(MouseFormat.MOUSE_FORMAT_XTERM, display.mouseFormat());
    assertEquals("", display.getWindowTitle());

    display.setCursor(3, 4);
    display.setCursorShape(CursorShape.STEADY_UNDERLINE);
    display.beep();
    display.scrollArea(1, 2, 3);
    display.setCursorVisible(false);
    display.useAlternateScreenBuffer(true);
    display.setWindowTitle("title");
    assertNull(display.getSelection());
    display.terminalMouseModeSet(MouseMode.MOUSE_REPORTING_HILITE);
    display.setMouseFormat(MouseFormat.MOUSE_FORMAT_SGR);
    assertFalse(display.ambiguousCharsAreDoubleWidth());

    assertEquals("title", display.getWindowTitle());
    assertEquals(CursorShape.STEADY_UNDERLINE, display.cursorShape());
    assertFalse(display.cursorVisible());
    assertEquals(MouseMode.MOUSE_REPORTING_HILITE, display.mouseMode());
    assertEquals(MouseFormat.MOUSE_FORMAT_SGR, display.mouseFormat());
  }
}
