package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;

import java.util.EnumSet;

/** 数值投影的边界回归：NUMERIC 槽来自公开 buffer fixture，不经过 UTF-8 通道。 */
class TerminalSnapshotProjectorTest {

  @Test
  void styledNulStaysUnitAndMissingTailIsDefaultEmpty() {
    TerminalLine line = new TerminalLine();
    TextStyle styled =
        new TextStyle(
            TerminalColor.index(2), TerminalColor.index(7), EnumSet.of(TextStyle.Option.BOLD));
    line.writeString(0, new CharBuffer("A\0B"), styled);

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 4);
    assertEquals(4, projected.slots().size());
    assertUnit(projected, 0, 'A');
    assertUnit(projected, 1, (char) 0);
    assertTrue(projected.slots().get(1).style().bold());
    assertEquals(new TerminalView.Color.Indexed(2), projected.slots().get(1).style().foreground());
    assertUnit(projected, 2, 'B');
    assertEquals(SlotKind.EMPTY, projected.slots().get(3).kind());
    assertEquals(TerminalView.Style.DEFAULT, projected.slots().get(3).style());
  }

  @Test
  void loneSurrogateIsPreservedAsNumericUnit() {
    TerminalLine line = new TerminalLine();
    line.writeString(0, new CharBuffer("\ud800A".toCharArray(), 0, 2), TextStyle.EMPTY);

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 2);
    assertUnit(projected, 0, (char) 0xd800);
    assertUnit(projected, 1, 'A');
  }

  @Test
  void dwcContinuationAndOverflowTruncationAreExplicit() {
    TerminalLine line = new TerminalLine();
    line.setWrapped(true);
    line.writeString(0, new CharBuffer("中\ue000X"), TextStyle.EMPTY);

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 3);
    assertTrue(projected.wrapped());
    assertEquals(3, projected.slots().size());
    assertUnit(projected, 0, '中');
    assertEquals(SlotKind.DWC, projected.slots().get(1).kind());
    assertEquals(0xe000, projected.slots().get(1).code());
    assertUnit(projected, 2, 'X');

    TerminalLine longLine = new TerminalLine();
    longLine.writeString(0, new CharBuffer("ABCDE"), TextStyle.EMPTY);
    assertEquals(4, TerminalSnapshotProjector.projectLine(longLine, 4).slots().size());
  }

  @Test
  void styleProjectionMapsColorsOptionsAndNull() {
    TextStyle style =
        new TextStyle(
            TerminalColor.rgb(1, 2, 3),
            TerminalColor.index(7),
            EnumSet.of(
                TextStyle.Option.BOLD,
                TextStyle.Option.DIM,
                TextStyle.Option.ITALIC,
                TextStyle.Option.UNDERLINED,
                TextStyle.Option.SLOW_BLINK,
                TextStyle.Option.INVERSE,
                TextStyle.Option.HIDDEN));
    TerminalView.Style projected = TerminalSnapshotProjector.projectStyle(style);
    assertEquals(new TerminalView.Color.Rgb(1, 2, 3), projected.foreground());
    assertEquals(new TerminalView.Color.Indexed(7), projected.background());
    assertTrue(projected.bold());
    assertTrue(projected.dim());
    assertTrue(projected.italic());
    assertTrue(projected.underline());
    assertTrue(projected.blink());
    assertTrue(projected.inverse());
    assertTrue(projected.hidden());
    assertFalse(projected.strikethrough());

    TerminalView.Style rapidBlink =
        TerminalSnapshotProjector.projectStyle(
            new TextStyle(null, null, EnumSet.of(TextStyle.Option.RAPID_BLINK)));
    assertTrue(rapidBlink.blink());
    assertNull(rapidBlink.foreground());
    assertEquals(TerminalView.Style.DEFAULT, TerminalSnapshotProjector.projectStyle(null));
  }

  @Test
  void everyPublicDisplayModeProjectsWithoutRenamingOrDroppingValues() {
    StyleState styles = new StyleState();
    TerminalTextBuffer buffer = new TerminalTextBuffer(8, 2, styles, 0);
    HeadlessTerminalDisplay display = new HeadlessTerminalDisplay();
    JediTerminal terminal = new JediTerminal(display, buffer, styles);
    for (CursorShape shape : CursorShape.values()) {
      display.setCursorShape(shape);
      assertEquals(
          shape.name(),
          TerminalSnapshotProjector.project(terminal, buffer, display, 1).cursorShape().name());
    }
    for (MouseMode mode : MouseMode.values()) {
      display.terminalMouseModeSet(mode);
      assertEquals(
          mode.name().substring("MOUSE_REPORTING_".length()),
          TerminalSnapshotProjector.project(terminal, buffer, display, 1)
              .inputModes()
              .mouseMode()
              .name());
    }
    for (MouseFormat format : MouseFormat.values()) {
      display.setMouseFormat(format);
      assertEquals(
          format.name().substring("MOUSE_FORMAT_".length()),
          TerminalSnapshotProjector.project(terminal, buffer, display, 1)
              .inputModes()
              .mouseFormat()
              .name());
    }
  }

  @Test
  void lineWithoutEntriesProjectsOnlyEmptySlots() {
    TerminalView.Line projected =
        TerminalSnapshotProjector.projectLine(TerminalLine.createEmpty(), 3);
    assertEquals(3, projected.slots().size());
    for (int x = 0; x < 3; x++) {
      assertEquals(SlotKind.EMPTY, projected.slots().get(x).kind());
      assertEquals(TerminalView.Style.DEFAULT, projected.slots().get(x).style());
    }
  }

  private static void assertUnit(TerminalView.Line line, int index, char expected) {
    assertEquals(SlotKind.UNIT, line.slots().get(index).kind());
    assertEquals(expected, line.slots().get(index).code());
  }
}
