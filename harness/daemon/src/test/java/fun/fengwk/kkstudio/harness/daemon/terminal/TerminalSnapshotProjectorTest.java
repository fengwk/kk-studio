package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.List;

/** 数值投影的边界回归：NUMERIC 槽来自公开 buffer fixture，不经过 UTF-8 通道。 */
class TerminalSnapshotProjectorTest {

  @Test
  void styledNulStaysUnitAndMissingTailIsDefaultEmpty() {
    TerminalLine line = new TerminalLine();
    TextStyle styled =
        new TextStyle(
            TerminalColor.index(2), TerminalColor.index(7), EnumSet.of(TextStyle.Option.BOLD));
    line.writeString(0, new CharBuffer("A\0B"), styled);

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 4, 1L);
    assertEquals(1L, projected.id());
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

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 2, 1L);
    assertUnit(projected, 0, (char) 0xd800);
    assertUnit(projected, 1, 'A');
  }

  @Test
  void dwcContinuationAndOverflowTruncationAreExplicit() {
    TerminalLine line = new TerminalLine();
    line.setWrapped(true);
    line.writeString(0, new CharBuffer("中\ue000X"), TextStyle.EMPTY);

    TerminalView.Line projected = TerminalSnapshotProjector.projectLine(line, 3, 1L);
    assertTrue(projected.wrapped());
    assertEquals(3, projected.slots().size());
    assertUnit(projected, 0, '中');
    assertEquals(SlotKind.DWC, projected.slots().get(1).kind());
    assertEquals(0xe000, projected.slots().get(1).code());
    assertUnit(projected, 2, 'X');

    TerminalLine longLine = new TerminalLine();
    longLine.writeString(0, new CharBuffer("ABCDE"), TextStyle.EMPTY);
    TerminalView.Line truncated = TerminalSnapshotProjector.projectLine(longLine, 4, 2L);
    assertEquals(2L, truncated.id());
    assertEquals(4, truncated.slots().size());
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
    TerminalSnapshotProjector projector = new TerminalSnapshotProjector();
    for (CursorShape shape : CursorShape.values()) {
      display.setCursorShape(shape);
      assertEquals(
          shape.name(), projector.project(terminal, buffer, display, 1).cursorShape().name());
    }
    for (MouseMode mode : MouseMode.values()) {
      display.terminalMouseModeSet(mode);
      assertEquals(
          mode.name().substring("MOUSE_REPORTING_".length()),
          projector.project(terminal, buffer, display, 1).inputModes().mouseMode().name());
    }
    for (MouseFormat format : MouseFormat.values()) {
      display.setMouseFormat(format);
      assertEquals(
          format.name().substring("MOUSE_FORMAT_".length()),
          projector.project(terminal, buffer, display, 1).inputModes().mouseFormat().name());
    }
  }

  @Test
  void lineWithoutEntriesProjectsOnlyEmptySlots() {
    TerminalView.Line projected =
        TerminalSnapshotProjector.projectLine(TerminalLine.createEmpty(), 3, 1L);
    assertEquals(3, projected.slots().size());
    for (int x = 0; x < 3; x++) {
      assertEquals(SlotKind.EMPTY, projected.slots().get(x).kind());
      assertEquals(TerminalView.Style.DEFAULT, projected.slots().get(x).style());
    }
  }

  @Test
  void repeatedCapturesKeepObservedIdentityAndPastViewHasOnlyImmutableData() {
    Fixture fixture = new Fixture(4);
    TerminalView initial = fixture.capture();
    assertEquals(List.of(1L, 2L, 3L), initial.lines().stream().map(TerminalView.Line::id).toList());
    assertEquals(initial, fixture.capture());
    assertEquals(3, fixture.projector.retainedLineCount());

    fixture.buffer.lock();
    try {
      fixture.buffer.getLine(0).writeString(0, new CharBuffer("X"), TextStyle.EMPTY);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView changed = fixture.capture();
    assertEquals(initial.lines().get(0).id(), changed.lines().get(0).id());
    assertEquals(SlotKind.EMPTY, initial.lines().get(0).slots().get(0).kind());
    assertUnit(changed.lines().get(0), 0, 'X');
    assertThrows(UnsupportedOperationException.class, () -> initial.lines().clear());
  }

  @Test
  void hundredsOfBlankScrollsTrimReferencesAndNeverGuessTextOverlap() {
    Fixture fixture = new Fixture(4);
    // 公开行 fixture 与 scrollArea 走真实存储，不用文本重叠或私有存储 API。
    TerminalLine oldLine = TerminalLine.createEmpty();
    fixture.buffer.lock();
    try {
      fixture.buffer.clearScreenAndHistoryBuffers();
      fixture.buffer.addLine(oldLine);
      fixture.buffer.addLine(TerminalLine.createEmpty());
      fixture.buffer.addLine(TerminalLine.createEmpty());
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView initial = fixture.capture();
    long oldId = initial.lines().get(0).id();
    long newestId = 3L;

    for (int scroll = 1; scroll <= 120; scroll++) {
      fixture.buffer.lock();
      try {
        fixture.buffer.scrollArea(1, -1, 3);
      } finally {
        fixture.buffer.unlock();
      }
      TerminalView current = fixture.capture();
      assertEquals(Math.min(scroll, 4), current.history());
      assertEquals(current.history() + current.rows(), fixture.projector.retainedLineCount());
      assertEquals(
          current.lines().size(),
          current.lines().stream().map(TerminalView.Line::id).distinct().count());
      long newId = current.lines().getLast().id();
      assertEquals(newestId + 1, newId);
      newestId = newId;
      if (scroll == 1) {
        fixture.buffer.lock();
        try {
          assertSame(oldLine, fixture.buffer.getLine(-1));
        } finally {
          fixture.buffer.unlock();
        }
        assertEquals(oldId, current.lines().get(0).id());
      }
      if (scroll > 4) {
        assertTrue(current.lines().stream().noneMatch(line -> line.id() == oldId));
      }
      assertEquals(current, fixture.capture());
    }

    // 重新放入已裁剪的同一个对象也必须获新号，证明旧引用映射确实被删除。
    fixture.buffer.lock();
    try {
      fixture.buffer.clearScreenAndHistoryBuffers();
      fixture.buffer.addLine(oldLine);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView reintroduced = fixture.capture();
    assertNotEquals(oldId, reintroduced.lines().get(0).id());
    assertEquals(newestId + 1, reintroduced.lines().get(0).id());
    assertEquals(3, fixture.projector.retainedLineCount());
    assertEquals(oldId, initial.lines().get(0).id());
  }

  @Test
  void alternateCaptureRetainsOnlyActiveRowsAndRestoredMainGetsNewIds() {
    Fixture fixture = new Fixture(4);
    fixture.capture();
    fixture.buffer.lock();
    try {
      fixture.buffer.scrollArea(1, -2, 3);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView main = fixture.capture();
    assertEquals(2, main.history());
    assertEquals(5, fixture.projector.retainedLineCount());

    fixture.buffer.lock();
    try {
      fixture.buffer.useAlternateBuffer(true);
      fixture.buffer.scrollArea(1, -6, 3);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView alternate = fixture.capture();
    assertTrue(alternate.alternate());
    assertEquals(0, alternate.history());
    assertEquals(3, fixture.projector.retainedLineCount());
    assertTrue(alternate.lines().getFirst().id() > main.lines().getLast().id());

    fixture.buffer.lock();
    try {
      fixture.buffer.useAlternateBuffer(false);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView restored = fixture.capture();
    assertEquals(main.history(), restored.history());
    assertEquals(main.lines().size(), fixture.projector.retainedLineCount());
    for (int row = 0; row < main.lines().size(); row++) {
      assertEquals(main.lines().get(row).slots(), restored.lines().get(row).slots());
      assertEquals(main.lines().get(row).wrapped(), restored.lines().get(row).wrapped());
      assertTrue(restored.lines().get(row).id() > alternate.lines().getLast().id());
    }
  }

  @Test
  void zeroHistoryScrollKeepsOnlyScreenReferences() {
    Fixture fixture = new Fixture(0);
    TerminalView initial = fixture.capture();
    fixture.buffer.lock();
    try {
      fixture.buffer.scrollArea(1, -1, 3);
    } finally {
      fixture.buffer.unlock();
    }
    TerminalView current = fixture.capture();
    assertEquals(0, current.history());
    assertEquals(3, fixture.projector.retainedLineCount());
    assertEquals(initial.lines().get(1).id(), current.lines().get(0).id());
    assertEquals(4L, current.lines().getLast().id());
  }

  @Test
  void failedCapturePropagatesAndDoesNotPublishPartialIdentityMap() {
    Fixture fixture = new Fixture(4);
    TerminalView initial = fixture.capture();
    fixture.buffer.lock();
    try {
      fixture.buffer.scrollArea(1, -1, 3);
    } finally {
      fixture.buffer.unlock();
    }
    assertThrows(
        NullPointerException.class,
        () -> fixture.projector.project(fixture.terminal, fixture.buffer, null, 1L));
    assertEquals(3, fixture.projector.retainedLineCount());
    TerminalView current = fixture.capture();
    assertEquals(initial.lines().get(0).id(), current.lines().get(0).id());
    assertTrue(current.lines().getLast().id() > 4L);
    assertEquals(4, fixture.projector.retainedLineCount());
  }

  /** 所有调用在测试 owner 线程；只使用 JediTerm 的公开缓冲与行 API。 */
  private static final class Fixture {
    private final TerminalTextBuffer buffer;
    private final HeadlessTerminalDisplay display = new HeadlessTerminalDisplay();
    private final JediTerminal terminal;
    private final TerminalSnapshotProjector projector = new TerminalSnapshotProjector();

    private Fixture(int history) {
      StyleState styles = new StyleState();
      buffer = new TerminalTextBuffer(8, 3, styles, history);
      terminal = new JediTerminal(display, buffer, styles);
    }

    private TerminalView capture() {
      return projector.project(terminal, buffer, display, 1L);
    }
  }

  private static void assertUnit(TerminalView.Line line, int index, char expected) {
    assertEquals(SlotKind.UNIT, line.slots().get(index).kind());
    assertEquals(expected, line.slots().get(index).code());
  }
}
