package fun.fengwk.kkstudio.harness.environment.terminal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.CursorShape;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.InputModes;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Line;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseFormat;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseMode;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Slot;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Style;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.Kind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.RowChange;

import java.util.ArrayList;
import java.util.List;

/** TerminalViewUpdate 的 RESET/PATCH 语义、行身份与 JS 安全整数边界回归。 */
class TerminalViewUpdateTest {

  private static final int COLUMNS = 6;
  private static final int ROWS = 2;

  @Test
  void resetRequiresExactHistoryAndScreenCoverage() {
    TerminalViewUpdate valid = TerminalViewSamples.reset();
    assertEquals(Kind.RESET, valid.type());
    assertNull(valid.baseVersion());
    assertEquals(0, valid.historyTrim());
    assertEquals(valid.history(), valid.historyAppend().size());
    assertEquals(valid.rows(), valid.screenRows().size());

    // 活动屏缺少行号 1：RESET 必须恰好覆盖 0..rows-1。
    assertThrows(
        IllegalArgumentException.class,
        () -> reset(1, 0, null, List.of(blank(10L)), List.of(rc(0, blank(11L)))));
    // historyAppend 数量与声明的 history 不一致。
    assertThrows(
        IllegalArgumentException.class, () -> reset(2, 0, null, List.of(blank(10L)), twoRows()));
    // RESET 不得声明 baseVersion。
    assertThrows(
        IllegalArgumentException.class, () -> reset(1, 0, 1L, List.of(blank(10L)), twoRows()));
    // RESET 不得裁剪历史。
    assertThrows(
        IllegalArgumentException.class, () -> reset(1, 1, null, List.of(blank(10L)), twoRows()));
    // 行号 0 出现两次而缺少行号 1。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(1, 0, null, List.of(blank(10L)), List.of(rc(0, blank(11L)), rc(0, blank(12L)))));
  }

  @Test
  void patchRequiresBaselineAndBoundedReplacement() {
    TerminalViewUpdate valid = TerminalViewSamples.patch();
    assertEquals(Kind.PATCH, valid.type());
    assertEquals(7L, valid.baseVersion());
    assertEquals(8L, valid.version());

    // PATCH 必须声明正数 baseVersion，且 version 严格大于它。
    assertThrows(IllegalArgumentException.class, () -> patch(0, null, 8L, 0, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> patch(0, 7L, 7L, 0, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> patch(0, 8L, 7L, 0, List.of(), List.of()));
    // 追加历史超过声明的 history。
    assertThrows(
        IllegalArgumentException.class, () -> patch(0, 7L, 8L, 0, List.of(blank(20L)), List.of()));
    // 行替换数量超过 rows。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            patch(
                0,
                7L,
                8L,
                0,
                List.of(),
                List.of(rc(0, blank(30L)), rc(1, blank(31L)), rc(0, blank(32L)))));
    assertDoesNotThrow(
        () -> patch(2, 7L, 8L, 0, List.of(blank(20L), blank(21L)), List.of(rc(0, blank(22L)))));
  }

  @Test
  void alternateScreenForbidsHistory() {
    assertDoesNotThrow(() -> build(Kind.RESET, true, null, 1L, 0, 0, List.of(), twoRows()));
    assertThrows(
        IllegalArgumentException.class,
        () -> build(Kind.RESET, true, null, 1L, 1, 0, List.of(blank(10L)), twoRows()));
    assertThrows(
        IllegalArgumentException.class,
        () -> build(Kind.PATCH, true, 1L, 2L, 0, 1, List.of(), List.of()));
  }

  @Test
  void lineShapeMustMatchColumnsAndKindCodes() {
    // 行槽数与 columns 不一致。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(
                1,
                0,
                null,
                List.of(TerminalViewSamples.line(10L, false, TerminalViewSamples.empty())),
                twoRows()));
    // DWC 必须使用 code 0xe000。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(
                1,
                0,
                null,
                List.of(
                    TerminalViewSamples.line(
                        10L, false, padded(new Slot(SlotKind.DWC, 0x1234, Style.DEFAULT)))),
                twoRows()));
    // EMPTY 必须使用 code 0。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(
                1,
                0,
                null,
                List.of(
                    TerminalViewSamples.line(
                        10L, false, padded(new Slot(SlotKind.EMPTY, 5, Style.DEFAULT)))),
                twoRows()));
  }

  @Test
  void lineIdsMustBePositiveSafeAndUnique() {
    assertThrows(IllegalArgumentException.class, () -> TerminalViewSamples.line(0L, false, wide()));
    assertThrows(
        IllegalArgumentException.class, () -> TerminalViewSamples.line(-1L, false, wide()));
    // 行 id 必须落在 JS 安全整数内（TerminalView.Line 自身只要求正数 long）。
    assertThrows(
        IllegalArgumentException.class,
        () -> reset(1, 0, null, List.of(blank(TerminalLimits.MAX_SAFE_INTEGER + 1)), twoRows()));
    // 历史与屏幕之间行 id 唯一。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(1, 0, null, List.of(blank(10L)), List.of(rc(0, blank(10L)), rc(1, blank(12L)))));
    // 屏幕替换之间行 id 唯一。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reset(1, 0, null, List.of(blank(10L)), List.of(rc(0, blank(11L)), rc(1, blank(11L)))));
  }

  @Test
  void versionRevisionAndBaseVersionStayWithinSafeInteger() {
    long over = TerminalLimits.MAX_SAFE_INTEGER + 1;
    assertThrows(
        IllegalArgumentException.class,
        () -> build(Kind.RESET, false, null, over, 1, 0, List.of(blank(10L)), twoRows(), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> build(Kind.RESET, false, null, 1L, 1, 0, List.of(blank(10L)), twoRows(), over));
    assertThrows(
        IllegalArgumentException.class, () -> patch(0, over, over + 1, 0, List.of(), List.of()));
    assertDoesNotThrow(
        () ->
            patch(
                0,
                TerminalLimits.MAX_SAFE_INTEGER - 1,
                TerminalLimits.MAX_SAFE_INTEGER,
                0,
                List.of(),
                List.of()));
  }

  @Test
  void cursorStaysWithinBoundsIncludingPendingWrap() {
    assertDoesNotThrow(() -> withCursor(COLUMNS, ROWS - 1));
    assertThrows(IllegalArgumentException.class, () -> withCursor(COLUMNS + 1, 0));
    assertThrows(IllegalArgumentException.class, () -> withCursor(0, ROWS));
    assertThrows(IllegalArgumentException.class, () -> withCursor(-1, 0));
  }

  @Test
  void dimensionsAndReferencesRejectOutOfRangeAndNull() {
    assertThrows(
        IllegalArgumentException.class, () -> withDimensions(TerminalLimits.MIN_COLUMNS - 1, ROWS));
    assertThrows(
        IllegalArgumentException.class, () -> withDimensions(COLUMNS, TerminalLimits.MIN_ROWS - 1));
    assertThrows(
        IllegalArgumentException.class, () -> withDimensions(TerminalLimits.MAX_COLUMNS + 1, ROWS));
    assertThrows(
        IllegalArgumentException.class, () -> withDimensions(COLUMNS, TerminalLimits.MAX_ROWS + 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new TerminalViewUpdate(
                null,
                TerminalViewSamples.TERMINAL_ID,
                TerminalViewSamples.STREAM_ID,
                null,
                1L,
                COLUMNS,
                ROWS,
                false,
                0,
                0,
                0,
                true,
                null,
                1L,
                modes(),
                0,
                List.of(),
                twoRows()));
    assertThrows(
        NullPointerException.class,
        () ->
            new TerminalViewUpdate(
                Kind.RESET,
                null,
                TerminalViewSamples.STREAM_ID,
                null,
                1L,
                COLUMNS,
                ROWS,
                false,
                0,
                0,
                0,
                true,
                null,
                1L,
                modes(),
                0,
                List.of(),
                twoRows()));
  }

  @Test
  void listsAreDefensivelyCopied() {
    List<Line> history = new ArrayList<>();
    history.add(blank(10L));
    List<RowChange> rows = new ArrayList<>();
    rows.add(rc(0, blank(11L)));
    rows.add(rc(1, blank(12L)));
    TerminalViewUpdate update = reset(1, 0, null, history, rows);
    history.clear();
    rows.clear();
    assertEquals(1, update.historyAppend().size());
    assertEquals(2, update.screenRows().size());
    assertThrows(UnsupportedOperationException.class, () -> update.historyAppend().add(blank(99L)));
    assertThrows(
        UnsupportedOperationException.class, () -> update.screenRows().add(rc(0, blank(99L))));
  }

  private static TerminalViewUpdate reset(
      int history,
      int historyTrim,
      Long baseVersion,
      List<Line> historyAppend,
      List<RowChange> screenRows) {
    return build(
        Kind.RESET, false, baseVersion, 1L, history, historyTrim, historyAppend, screenRows);
  }

  private static TerminalViewUpdate patch(
      int history,
      Long baseVersion,
      long version,
      int historyTrim,
      List<Line> historyAppend,
      List<RowChange> screenRows) {
    return build(
        Kind.PATCH, false, baseVersion, version, history, historyTrim, historyAppend, screenRows);
  }

  private static TerminalViewUpdate build(
      Kind kind,
      boolean alternate,
      Long baseVersion,
      long version,
      int history,
      int historyTrim,
      List<Line> historyAppend,
      List<RowChange> screenRows) {
    return build(
        kind, alternate, baseVersion, version, history, historyTrim, historyAppend, screenRows, 1L);
  }

  private static TerminalViewUpdate build(
      Kind kind,
      boolean alternate,
      Long baseVersion,
      long version,
      int history,
      int historyTrim,
      List<Line> historyAppend,
      List<RowChange> screenRows,
      long inputModeRevision) {
    return new TerminalViewUpdate(
        kind,
        TerminalViewSamples.TERMINAL_ID,
        TerminalViewSamples.STREAM_ID,
        baseVersion,
        version,
        COLUMNS,
        ROWS,
        alternate,
        history,
        COLUMNS,
        ROWS - 1,
        true,
        CursorShape.STEADY_BLOCK,
        inputModeRevision,
        modes(),
        historyTrim,
        historyAppend,
        screenRows);
  }

  private static TerminalViewUpdate withCursor(int cursorX, int cursorY) {
    return new TerminalViewUpdate(
        Kind.RESET,
        TerminalViewSamples.TERMINAL_ID,
        TerminalViewSamples.STREAM_ID,
        null,
        1L,
        COLUMNS,
        ROWS,
        false,
        0,
        cursorX,
        cursorY,
        true,
        null,
        1L,
        modes(),
        0,
        List.of(),
        twoRows());
  }

  private static TerminalViewUpdate withDimensions(int columns, int rows) {
    return new TerminalViewUpdate(
        Kind.RESET,
        TerminalViewSamples.TERMINAL_ID,
        TerminalViewSamples.STREAM_ID,
        null,
        1L,
        columns,
        rows,
        false,
        0,
        0,
        0,
        true,
        null,
        1L,
        modes(),
        0,
        List.of(),
        twoRows());
  }

  private static Line blank(long id) {
    return TerminalViewSamples.line(id, false, wide());
  }

  private static Slot[] wide() {
    Slot[] slots = new Slot[COLUMNS];
    for (int index = 0; index < COLUMNS; index++) {
      slots[index] = TerminalViewSamples.empty();
    }
    return slots;
  }

  private static Slot[] padded(Slot slot) {
    Slot[] slots = wide();
    slots[0] = slot;
    return slots;
  }

  private static List<RowChange> twoRows() {
    return List.of(rc(0, blank(11L)), rc(1, blank(12L)));
  }

  private static RowChange rc(int row, Line line) {
    return new RowChange(row, line);
  }

  private static InputModes modes() {
    return new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM);
  }
}
