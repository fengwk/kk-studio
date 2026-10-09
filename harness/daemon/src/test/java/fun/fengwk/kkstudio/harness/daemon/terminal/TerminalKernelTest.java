package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.CursorShape;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseFormat;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseMode;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 内核的确定性行为回归：数值槽、样式、历史/备用缓冲与输入模式。 */
class TerminalKernelTest {

  private static final int TIMEOUT_SECONDS = 5;

  private final List<byte[]> responses = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void shutDownExecutors() {
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
  }

  @Test
  void asciiProjectsNumericSlotsAndPaddedEmptyTail() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("ABC")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView view = snapshot(kernel);
      assertEquals(8, view.columns());
      assertEquals(4, view.rows());
      assertEquals(4, view.lines().size());
      assertEquals(3, view.cursorX());
      assertEquals(0, view.cursorY());
      assertFalse(view.alternate());
      assertEquals(0, view.history());
      TerminalView.Line line = view.lines().get(0);
      assertFalse(line.wrapped());
      assertEquals(8, line.slots().size());
      assertEquals(SlotKind.UNIT, line.slots().get(0).kind());
      assertEquals('A', line.slots().get(0).code());
      assertEquals('C', line.slots().get(2).code());
      for (int x = 3; x < 8; x++) {
        assertEquals(SlotKind.EMPTY, line.slots().get(x).kind());
        assertEquals(0, line.slots().get(x).code());
        assertEquals(TerminalView.Style.DEFAULT, line.slots().get(x).style());
      }
    }
  }

  @Test
  void trueColorAndEverySupportedStyleProjectIndependently() throws Exception {
    try (TerminalKernel kernel = kernel(16, 2)) {
      kernel.feed(utf8("\033[1;2;3;4;5;7;8;38;2;123;45;67;48;2;9;8;7mS\033[0mT")).get();
      TerminalView.Line line = snapshot(kernel).lines().get(0);
      TerminalView.Style style = line.slots().get(0).style();
      assertTrue(style.bold());
      assertTrue(style.dim());
      assertTrue(style.italic());
      assertTrue(style.underline());
      assertTrue(style.blink());
      assertTrue(style.inverse());
      assertTrue(style.hidden());
      assertFalse(style.strikethrough());
      assertEquals(new TerminalView.Color.Rgb(123, 45, 67), style.foreground());
      assertEquals(new TerminalView.Color.Rgb(9, 8, 7), style.background());
      assertEquals(TerminalView.Style.DEFAULT, line.slots().get(1).style());
    }
  }

  @Test
  void indexedColorsKeepPaletteIndex() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("\033[31;44mX")).get();
      TerminalView.Style style = snapshot(kernel).lines().get(0).slots().get(0).style();
      assertEquals(new TerminalView.Color.Indexed(1), style.foreground());
      assertEquals(new TerminalView.Color.Indexed(4), style.background());
    }
  }

  @Test
  void cjkWideCharacterKeepsContinuationSlotUntilOverwritten() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("中")).get();
      TerminalView.Line wide = snapshot(kernel).lines().get(0);
      assertEquals(SlotKind.UNIT, wide.slots().get(0).kind());
      assertEquals('中', wide.slots().get(0).code());
      assertEquals(SlotKind.DWC, wide.slots().get(1).kind());
      assertEquals(0xe000, wide.slots().get(1).code());

      kernel.feed(utf8("\bX")).get();
      TerminalView.Line overwritten = snapshot(kernel).lines().get(0);
      assertEquals(SlotKind.UNIT, overwritten.slots().get(0).kind());
      assertEquals('中', overwritten.slots().get(0).code());
      assertEquals(SlotKind.UNIT, overwritten.slots().get(1).kind());
      assertEquals('X', overwritten.slots().get(1).code());
    }
  }

  @Test
  void surrogatePairUsesTwoUnitsAndFeffStaysExplicit() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("😀\ufeffZ")).get();
      TerminalView.Line line = snapshot(kernel).lines().get(0);
      assertEquals(0xd83d, line.slots().get(0).code());
      assertEquals(SlotKind.UNIT, line.slots().get(0).kind());
      assertEquals(0xde00, line.slots().get(1).code());
      assertEquals(0xfeff, line.slots().get(2).code());
      assertEquals('Z', line.slots().get(3).code());
    }
  }

  @Test
  void combiningCharacterWithoutPrecomposedFormKeepsTwoUnits() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("q\u0301Z")).get();
      TerminalView.Line line = snapshot(kernel).lines().get(0);
      assertEquals('q', line.slots().get(0).code());
      assertEquals(0x0301, line.slots().get(1).code());
      assertEquals('Z', line.slots().get(2).code());
    }
  }

  @Test
  void pendingWrapCursorReachesColumnCountWithoutRelocation() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("12345678")).get();
      TerminalView view = snapshot(kernel);
      assertEquals(8, view.cursorX());
      assertEquals(0, view.cursorY());
      assertEquals('8', view.lines().get(0).slots().get(7).code());
    }
  }

  @Test
  void repeatedLinesScrollIntoBoundedHistory() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("\r\n".repeat(24) + "end")).get();
      TerminalView view = snapshot(kernel);
      assertEquals(8, view.history());
      assertEquals(12, view.lines().size());
      assertEquals(8, view.lines().get(0).slots().size());
    }
  }

  @Test
  void alternateBufferThenRestoreProjectsMainScreen() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("MAIN")).get();
      kernel.feed(utf8("\033[?1049h\rALT")).get();
      TerminalView alternate = snapshot(kernel);
      assertTrue(alternate.alternate());
      assertEquals(0, alternate.history());
      assertEquals('A', alternate.lines().get(0).slots().get(0).code());
      assertEquals('T', alternate.lines().get(0).slots().get(2).code());

      kernel.feed(utf8("\033[?1049l")).get();
      TerminalView main = snapshot(kernel);
      assertFalse(main.alternate());
      assertEquals('M', main.lines().get(0).slots().get(0).code());
      assertEquals('N', main.lines().get(0).slots().get(3).code());
    }
  }

  @Test
  void resizeReflowsToRequestedSize() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("123456789ABC")).get();
      kernel.resize(6, 4).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView view = snapshot(kernel);
      assertEquals(6, view.columns());
      assertEquals(4, view.rows());
      for (TerminalView.Line line : view.lines()) {
        assertEquals(6, line.slots().size());
      }
    }
  }

  @Test
  void inputModesAndRevisionTrackOnlyModeOrSizeChanges() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      TerminalView initial = snapshot(kernel);
      assertEquals(1, initial.inputModeRevision());
      assertFalse(initial.inputModes().applicationCursor());
      assertEquals(MouseMode.NONE, initial.inputModes().mouseMode());
      assertEquals(MouseFormat.XTERM, initial.inputModes().mouseFormat());

      // 每个实际改变输入模式的 owner step 恰好 +1；snapshot 本身不递增。
      feedAndExpectRevisionIncrement(kernel, "\033[?1h");
      assertTrue(snapshot(kernel).inputModes().applicationCursor());
      feedAndExpectRevisionIncrement(kernel, "\033[?1000h");
      assertEquals(MouseMode.NORMAL, snapshot(kernel).inputModes().mouseMode());
      feedAndExpectRevisionIncrement(kernel, "\033[?1006h");
      assertEquals(MouseFormat.SGR, snapshot(kernel).inputModes().mouseFormat());
      feedAndExpectRevisionIncrement(kernel, "\033[?2004h");
      assertTrue(snapshot(kernel).inputModes().bracketedPaste());
      feedAndExpectRevisionIncrement(kernel, "\033[20h");
      assertTrue(snapshot(kernel).inputModes().autoNewLine());
      feedAndExpectRevisionIncrement(kernel, "\033[?1039h");
      assertTrue(snapshot(kernel).inputModes().altSendsEscape());
      assertEquals(7, snapshot(kernel).inputModeRevision());

      // 普通文本输出不改变输入模式或 revision。
      kernel.feed(utf8("plain")).get();
      assertEquals(7, snapshot(kernel).inputModeRevision());
      assertEquals(7, snapshot(kernel).inputModeRevision());

      // 权威尺寸改变 +1。
      kernel.resize(10, 3).get();
      assertEquals(8, snapshot(kernel).inputModeRevision());
    }
  }

  @Test
  void cursorShapeAndVisibilityComeFromDisplayCallbacks() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.feed(utf8("\033[2 q\033[?25l")).get();
      TerminalView view = snapshot(kernel);
      assertFalse(view.cursorVisible());
      assertEquals(CursorShape.STEADY_BLOCK, view.cursorShape());
      kernel.feed(utf8("\033[?25h")).get();
      assertTrue(snapshot(kernel).cursorVisible());
    }
  }

  @Test
  void keyEncodingFollowsApplicationCursorMode() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      assertEquals(
          "\033[A", new String(kernel.encodeKey(38, 0).get(), StandardCharsets.ISO_8859_1));
      kernel.feed(utf8("\033[?1h")).get();
      assertEquals(
          "\033OA", new String(kernel.encodeKey(38, 0).get(), StandardCharsets.ISO_8859_1));
      assertNull(kernel.encodeKey(-1, 0).get());
    }
  }

  @Test
  void wideCharactersPushedBackAtLineEndStillWrapWithExactSlots() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      kernel.feed(utf8("中".repeat(8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalView view = snapshot(kernel);
      for (int row = 0; row < 2; row++) {
        for (int col = 0; col < 8; col += 2) {
          assertEquals('中', view.lines().get(row).slots().get(col).code());
          assertEquals(SlotKind.DWC, view.lines().get(row).slots().get(col + 1).kind());
        }
      }
      assertTrue(view.lines().get(0).wrapped());
    }
  }

  @Test
  void resizingHashCollisionDimensionsStillAdvancesRevision() throws Exception {
    try (TerminalKernel kernel = kernel(8, 2)) {
      kernel.resize(7, 33).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(2, snapshot(kernel).inputModeRevision());
      kernel.resize(7, 33).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(2, snapshot(kernel).inputModeRevision());
    }
  }

  @Test
  void applicationKeypadAndResetPublishActualModes() throws Exception {
    try (TerminalKernel kernel = kernel(8, 4)) {
      feedAndExpectRevisionIncrement(kernel, "\033=");
      assertTrue(snapshot(kernel).inputModes().applicationKeypad());
      feedAndExpectRevisionIncrement(kernel, "\033>");
      assertFalse(snapshot(kernel).inputModes().applicationKeypad());
      feedAndExpectRevisionIncrement(kernel, "\033[?1h");
      feedAndExpectRevisionIncrement(kernel, "\033c");
      assertFalse(snapshot(kernel).inputModes().applicationCursor());
    }
  }

  @Test
  void invalidConstructorSizesAndHistoryAreRejectedBeforeScheduling() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalKernel(0, 2, 8, executor, responses::add));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalKernel(8, 0, 8, executor, responses::add));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TerminalKernel(8, 2, -1, executor, responses::add));
  }

  @Test
  void invalidArgumentsAreRejectedSynchronously() {
    try (TerminalKernel kernel = kernel(8, 2)) {
      assertTrue(
          throwsIllegalArgument(
              () -> kernel.feed(new byte[TerminalKernel.MAX_INPUT_CHUNK_BYTES + 1])));
      assertTrue(throwsIllegalArgument(() -> kernel.resize(0, 2)));
    }
  }

  private TerminalKernel kernel(int columns, int rows) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return new TerminalKernel(columns, rows, 8, executor, responses::add);
  }

  private static TerminalView snapshot(TerminalKernel kernel) throws Exception {
    return kernel.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void feedAndExpectRevisionIncrement(TerminalKernel kernel, String escape)
      throws Exception {
    long before = snapshot(kernel).inputModeRevision();
    kernel.feed(utf8(escape)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals(before + 1, snapshot(kernel).inputModeRevision());
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static boolean throwsIllegalArgument(Runnable action) {
    try {
      action.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }
}
