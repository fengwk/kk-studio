package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.InputModes;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Line;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseFormat;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.MouseMode;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Slot;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.SlotKind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView.Style;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.Kind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.RowChange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** {@link TerminalViewStream} 的确定性回归：RESET 基线、单在途额度、ACK 围栏与基于行身份的增量/回退。 */
class TerminalViewStreamTest {

  private static final int TIMEOUT_SECONDS = 5;
  private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID STREAM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER_STREAM_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final InputModes MODES =
      new InputModes(false, false, false, false, false, MouseMode.NONE, MouseFormat.XTERM);

  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void shutDownExecutors() {
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
  }

  @Test
  void newStreamEmitsVersionOneResetAndHasNoBaselineUntilAck() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    assertFalse(stream.isApplied());
    assertFalse(stream.hasInFlight());
    assertEquals(0L, stream.lastAppliedVersion());

    List<Line> lines = List.of(line(10L, 6, 'h'), line(11L, 6, 'A'), line(12L, 6, 'B'));
    TerminalView view = view(6, 2, 1, 1, 0, 1L, false, lines);

    TerminalViewUpdate reset = stream.offer(view).orElseThrow();
    assertEquals(Kind.RESET, reset.type());
    assertNull(reset.baseVersion());
    assertEquals(1L, reset.version());
    assertEquals(TERMINAL_ID, reset.terminalId());
    assertEquals(STREAM_ID, reset.streamId());
    assertEquals(1, reset.history());
    assertEquals(0, reset.historyTrim());
    assertEquals(List.of(lines.get(0)), reset.historyAppend());
    assertEquals(
        List.of(new RowChange(0, lines.get(1)), new RowChange(1, lines.get(2))),
        reset.screenRows());

    // 首个 RESET 未确认前没有基线，也未推进版本。
    assertTrue(stream.hasInFlight());
    assertFalse(stream.isApplied());
    assertEquals(0L, stream.lastAppliedVersion());

    assertTrue(stream.applied(STREAM_ID, 1L));
    assertTrue(stream.isApplied());
    assertFalse(stream.hasInFlight());
    assertEquals(1L, stream.lastAppliedVersion());

    // 与已确认基线完全相同的画面不再发送，也不推进版本。
    assertTrue(stream.offer(view).isEmpty());
    assertFalse(stream.hasInFlight());
    assertEquals(1L, stream.lastAppliedVersion());
  }

  @Test
  void ackRequiresExactStreamIdAndInflightVersion() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    TerminalView view =
        view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B')));
    assertTrue(stream.offer(view).isPresent());

    assertFalse(stream.applied(OTHER_STREAM_ID, 1L));
    assertFalse(stream.applied(STREAM_ID, 2L));
    assertFalse(stream.applied(null, 1L));
    assertTrue(stream.hasInFlight());
    assertEquals(0L, stream.lastAppliedVersion());

    assertTrue(stream.applied(STREAM_ID, 1L));
    // 重复 ACK 不能再次推进，也不能清理或改写已提升的基线。
    assertFalse(stream.applied(STREAM_ID, 1L));
    assertEquals(1L, stream.lastAppliedVersion());
    assertFalse(stream.hasInFlight());
  }

  @Test
  void inflightBlocksFurtherOffersAndIgnoresLatestView() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    TerminalView first =
        view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B')));
    TerminalView latest =
        view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'X'), line(12L, 6, 'Y')));
    assertTrue(stream.offer(first).isPresent());

    // 有在途时不生成、不保存最新画面。
    assertTrue(stream.offer(latest).isEmpty());
    assertTrue(stream.hasInFlight());

    assertTrue(stream.applied(STREAM_ID, 1L));
    TerminalViewUpdate patch = stream.offer(latest).orElseThrow();
    assertEquals(Kind.PATCH, patch.type());
    assertEquals(1L, patch.baseVersion());
    assertEquals(2L, patch.version());
    assertEquals(2, patch.screenRows().size());
  }

  @Test
  void changedScreenRowBecomesPatchWithOnlyThatRow() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream, view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B'))));

    TerminalViewUpdate patch =
        stream
            .offer(view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'Z'))))
            .orElseThrow();
    assertEquals(Kind.PATCH, patch.type());
    assertEquals(1L, patch.baseVersion());
    assertEquals(2L, patch.version());
    assertEquals(0, patch.historyTrim());
    assertTrue(patch.historyAppend().isEmpty());
    assertEquals(List.of(new RowChange(1, line(12L, 6, 'Z'))), patch.screenRows());
  }

  @Test
  void cursorOnlyChangeBecomesMetadataOnlyPatch() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    List<Line> lines = List.of(line(11L, 6, 'A'), line(12L, 6, 'B'));
    acknowledge(stream, view(6, 2, 0, 0, 0, 1L, false, lines));

    TerminalViewUpdate patch = stream.offer(view(6, 2, 0, 3, 1, 1L, false, lines)).orElseThrow();
    assertEquals(Kind.PATCH, patch.type());
    assertEquals(3, patch.cursorX());
    assertEquals(1, patch.cursorY());
    assertTrue(patch.historyAppend().isEmpty());
    assertTrue(patch.screenRows().isEmpty());
  }

  @Test
  void scrollingIntoEmptyHistoryAppendsOldScreenRowAndReplacesScreen() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream, view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B'))));

    TerminalViewUpdate patch =
        stream
            .offer(
                view(
                    6,
                    2,
                    1,
                    0,
                    0,
                    1L,
                    false,
                    List.of(line(11L, 6, 'A'), line(12L, 6, 'B'), line(13L, 6, 'C'))))
            .orElseThrow();
    assertEquals(Kind.PATCH, patch.type());
    assertEquals(0, patch.historyTrim());
    assertEquals(List.of(line(11L, 6, 'A')), patch.historyAppend());
    assertEquals(2, patch.screenRows().size());
  }

  @Test
  void historyTrimKeepsRetainedSuffixAndAppendsOnlyNewLines() {
    int columns = 6;
    List<Line> base = new ArrayList<>();
    for (int i = 0; i < TerminalLimits.MAX_HISTORY_LINES; i++) {
      base.add(line(1L + i, columns, 'a'));
    }
    base.add(line(1001L, columns, 'x'));
    base.add(line(1002L, columns, 'y'));
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    assertTrue(stream.offer(view(columns, 2, 512, 0, 0, 1L, false, base)).isPresent());
    assertTrue(stream.applied(STREAM_ID, 1L));

    List<Line> next = new ArrayList<>();
    for (int i = 500; i < TerminalLimits.MAX_HISTORY_LINES; i++) {
      next.add(line(1L + i, columns, 'a'));
    }
    for (int i = 0; i < 500; i++) {
      next.add(line(2001L + i, columns, 'z'));
    }
    next.add(line(1001L, columns, 'x'));
    next.add(line(1002L, columns, 'y'));

    TerminalViewUpdate patch =
        stream.offer(view(columns, 2, 512, 0, 0, 1L, false, next)).orElseThrow();
    assertEquals(Kind.PATCH, patch.type());
    assertEquals(500, patch.historyTrim());
    assertEquals(500, patch.historyAppend().size());
    assertEquals(2001L, patch.historyAppend().get(0).id());
    assertTrue(patch.screenRows().isEmpty());
  }

  @Test
  void disconnectedHistoryWithoutOverlapFallsBackToReset() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream,
        view(
            6,
            2,
            1,
            0,
            0,
            1L,
            false,
            List.of(line(10L, 6, 'h'), line(11L, 6, 'A'), line(12L, 6, 'B'))));

    // 屏幕行 id 仍共享，但新历史首行在旧历史中找不到重叠点：不能凭文本猜测。
    TerminalViewUpdate update =
        stream
            .offer(
                view(
                    6,
                    2,
                    1,
                    0,
                    0,
                    1L,
                    false,
                    List.of(line(20L, 6, 'h'), line(11L, 6, 'A'), line(12L, 6, 'B'))))
            .orElseThrow();
    assertEquals(Kind.RESET, update.type());
    assertNull(update.baseVersion());
  }

  @Test
  void mutatedRetainedHistoryFallsBackToReset() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream,
        view(
            6,
            2,
            2,
            0,
            0,
            1L,
            false,
            List.of(line(10L, 6, 'h'), line(11L, 6, 'A'), line(12L, 6, 'B'), line(13L, 6, 'C'))));

    TerminalViewUpdate update =
        stream
            .offer(
                view(
                    6,
                    2,
                    2,
                    0,
                    0,
                    1L,
                    false,
                    List.of(
                        line(10L, 6, 'h'),
                        line(11L, 6, 'Z'),
                        line(12L, 6, 'B'),
                        line(13L, 6, 'C'))))
            .orElseThrow();
    assertEquals(Kind.RESET, update.type());
  }

  @Test
  void historyDisappearingFallsBackToReset() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream,
        view(
            6,
            2,
            1,
            0,
            0,
            1L,
            false,
            List.of(line(10L, 6, 'h'), line(11L, 6, 'A'), line(12L, 6, 'B'))));

    // 旧历史非空而新历史为空：结构不连续，退回整屏基线。
    TerminalViewUpdate update =
        stream
            .offer(view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B'))))
            .orElseThrow();
    assertEquals(Kind.RESET, update.type());
  }

  @Test
  void resizeFallsBackToReset() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream, view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B'))));

    TerminalViewUpdate update =
        stream
            .offer(view(8, 2, 0, 0, 0, 1L, false, List.of(line(11L, 8, 'A'), line(12L, 8, 'B'))))
            .orElseThrow();
    assertEquals(Kind.RESET, update.type());
    assertEquals(8, update.columns());
  }

  @Test
  void noSharedLineIdFallsBackToResetEvenWithMatchingSize() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    acknowledge(
        stream, view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B'))));

    // 主/备用屏往返：尺寸和文本都可能相同，但没有任何实际共享行 id，必须整屏重置。
    TerminalViewUpdate update =
        stream
            .offer(view(6, 2, 0, 0, 0, 1L, false, List.of(line(21L, 6, 'A'), line(22L, 6, 'B'))))
            .orElseThrow();
    assertEquals(Kind.RESET, update.type());
  }

  @Test
  void decreasingInputModeRevisionIsRejected() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    List<Line> lines = List.of(line(11L, 6, 'A'), line(12L, 6, 'B'));
    acknowledge(stream, view(6, 2, 0, 0, 0, 5L, false, lines));

    assertThrows(
        IllegalArgumentException.class, () -> stream.offer(view(6, 2, 0, 0, 0, 4L, false, lines)));
  }

  @Test
  void closedStreamNeverOffersOrApplies() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    TerminalView view =
        view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(12L, 6, 'B')));
    assertTrue(stream.offer(view).isPresent());

    stream.close();
    stream.close();
    assertTrue(stream.offer(view).isEmpty());
    assertFalse(stream.applied(STREAM_ID, 1L));
    assertFalse(stream.isApplied());
  }

  @Test
  void malformedViewsAreRejectedExplicitly() {
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    List<Line> twoRows = List.of(line(11L, 6, 'A'), line(12L, 6, 'B'));

    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(4, 2, 0, 0, 0, 1L, false, twoRows)));
    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(6, 1, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A')))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            stream.offer(
                view(6, 2, TerminalLimits.MAX_HISTORY_LINES + 1, 0, 0, 1L, false, twoRows)));
    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A')))));
    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(6, 2, 0, 7, 0, 1L, false, twoRows)));
    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(6, 2, 0, 0, 2, 1L, false, twoRows)));
    assertThrows(
        IllegalArgumentException.class,
        () -> stream.offer(view(6, 2, 0, 0, 0, 0L, false, twoRows)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            stream.offer(
                view(
                    6,
                    2,
                    1,
                    0,
                    0,
                    1L,
                    true,
                    List.of(line(11L, 6, 'A'), line(12L, 6, 'B'), line(13L, 6, 'C')))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            stream.offer(
                view(
                    6,
                    2,
                    0,
                    0,
                    0,
                    1L,
                    false,
                    List.of(
                        new Line(TerminalLimits.MAX_SAFE_INTEGER + 1, false, slots(6, 'A')),
                        line(12L, 6, 'B')))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            stream.offer(
                view(
                    6,
                    2,
                    0,
                    0,
                    0,
                    1L,
                    false,
                    List.of(new Line(11L, false, slots(5, 'A')), line(12L, 6, 'B')))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            stream.offer(
                view(6, 2, 0, 0, 0, 1L, false, List.of(line(11L, 6, 'A'), line(11L, 6, 'B')))));
  }

  @Test
  void realKernelScrollProducesBoundedPatchesWithTrim() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    TerminalViewStream stream = new TerminalViewStream(TERMINAL_ID, STREAM_ID);
    try (TerminalKernel kernel = new TerminalKernel(8, 4, 8, executor, ignored -> {})) {
      kernel.feed(utf8("\033[2J\033[4;1H")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalViewUpdate reset = stream.offer(snapshot(kernel)).orElseThrow();
      assertEquals(Kind.RESET, reset.type());
      assertEquals(1L, reset.version());
      assertTrue(stream.applied(STREAM_ID, reset.version()));

      // 首次滚动把原始屏幕行整体移入历史；历史为空时整段追加，不重发未变屏幕。
      kernel.feed(utf8("\r\n".repeat(8))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalViewUpdate append = stream.offer(snapshot(kernel)).orElseThrow();
      assertEquals(Kind.PATCH, append.type());
      assertEquals(2L, append.version());
      assertEquals(0, append.historyTrim());
      assertEquals(8, append.historyAppend().size());
      assertTrue(stream.applied(STREAM_ID, append.version()));

      // 超过历史上限后继续滚动：旧历史前缀被裁剪，只有新增历史进入 PATCH。
      kernel.feed(utf8("\r\n".repeat(5))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      TerminalViewUpdate trim = stream.offer(snapshot(kernel)).orElseThrow();
      assertEquals(Kind.PATCH, trim.type());
      assertEquals(3L, trim.version());
      assertTrue(trim.historyTrim() > 0);
      assertEquals(8, trim.history());
      assertTrue(stream.applied(STREAM_ID, trim.version()));
      assertEquals(3L, stream.lastAppliedVersion());
    }
  }

  private void acknowledge(TerminalViewStream stream, TerminalView view) {
    TerminalViewUpdate reset = stream.offer(view).orElseThrow();
    assertEquals(Kind.RESET, reset.type());
    assertTrue(stream.applied(STREAM_ID, reset.version()));
  }

  private static TerminalView view(
      int columns,
      int rows,
      int history,
      int cursorX,
      int cursorY,
      long inputModeRevision,
      boolean alternate,
      List<Line> lines) {
    return new TerminalView(
        columns,
        rows,
        cursorX,
        cursorY,
        alternate,
        history,
        lines,
        true,
        null,
        inputModeRevision,
        MODES);
  }

  private static Line line(long id, int columns, int code) {
    return new Line(id, false, slots(columns, code));
  }

  private static List<Slot> slots(int columns, int... codes) {
    List<Slot> slots = new ArrayList<>(columns);
    for (int code : codes) {
      slots.add(new Slot(SlotKind.UNIT, code, Style.DEFAULT));
    }
    while (slots.size() < columns) {
      slots.add(Slot.EMPTY);
    }
    return slots;
  }

  private static TerminalView snapshot(TerminalKernel kernel) throws Exception {
    return kernel.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
