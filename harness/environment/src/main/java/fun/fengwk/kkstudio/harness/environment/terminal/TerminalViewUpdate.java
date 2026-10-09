package fun.fengwk.kkstudio.harness.environment.terminal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 从真实 JediTerm 数值投影派生的一次 RESET/PATCH 结构化画面更新。
 *
 * <p>{@link Kind#RESET} 同时替换整个活动屏与历史：{@code baseVersion=null}、{@code historyTrim=0}、{@code
 * historyAppend.size()=history}，且 {@code screenRows} 恰好覆盖 {@code 0..rows-1} 每一行一次。{@link
 * Kind#PATCH} 只声明对旧 基线的有界增量：{@code baseVersion>=1}、{@code version>baseVersion}、{@code historyTrim}
 * 与 {@code historyAppend} 描述历史 裁剪与追加，{@code screenRows} 是行号不重复的行替换。与浏览器旧历史的跨消息一致性由镜像/流状态机负责，codec
 * 不伪造旧基线。
 *
 * <p>行复用 {@link TerminalView.Line}，其身份是唯一 owner 从实际 TerminalLine 引用观察所得的不可变正数；本模型只额外要求它落在 JS
 * 安全整数范围内。所有 List 在构造时防御复制，嵌套类型本身深不可变，因此整个更新不可变。
 */
public record TerminalViewUpdate(
    Kind type,
    UUID terminalId,
    UUID streamId,
    Long baseVersion,
    long version,
    int columns,
    int rows,
    boolean alternate,
    int history,
    int cursorX,
    int cursorY,
    boolean cursorVisible,
    TerminalView.CursorShape cursorShape,
    long inputModeRevision,
    TerminalView.InputModes inputModes,
    int historyTrim,
    List<TerminalView.Line> historyAppend,
    List<RowChange> screenRows) {

  /** 消息语义：整屏重置或基于旧基线的增量。 */
  public enum Kind {
    RESET,
    PATCH
  }

  /** 活动屏行替换：{@code row} 是该消息 {@code 0..rows-1} 内的行号，{@code line} 是替换后的整行。 */
  public record RowChange(int row, TerminalView.Line line) {

    public RowChange {
      Objects.requireNonNull(line, "line");
    }
  }

  public TerminalViewUpdate {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(terminalId, "terminalId");
    Objects.requireNonNull(streamId, "streamId");
    Objects.requireNonNull(inputModes, "inputModes");
    historyAppend = List.copyOf(historyAppend);
    screenRows = List.copyOf(screenRows);
    if (columns < TerminalLimits.MIN_COLUMNS || columns > TerminalLimits.MAX_COLUMNS) {
      throw new IllegalArgumentException("columns out of range: " + columns);
    }
    if (rows < TerminalLimits.MIN_ROWS || rows > TerminalLimits.MAX_ROWS) {
      throw new IllegalArgumentException("rows out of range: " + rows);
    }
    if (history < 0 || history > TerminalLimits.MAX_HISTORY_LINES) {
      throw new IllegalArgumentException("history out of range: " + history);
    }
    if (cursorX < 0 || cursorX > columns) {
      throw new IllegalArgumentException("cursorX out of range: " + cursorX);
    }
    if (cursorY < 0 || cursorY > rows - 1) {
      throw new IllegalArgumentException("cursorY out of range: " + cursorY);
    }
    if (version < 1 || version > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("version out of range: " + version);
    }
    if (inputModeRevision < 1 || inputModeRevision > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("inputModeRevision out of range: " + inputModeRevision);
    }
    if (historyTrim < 0 || historyTrim > TerminalLimits.MAX_HISTORY_LINES) {
      throw new IllegalArgumentException("historyTrim out of range: " + historyTrim);
    }
    for (TerminalView.Line line : historyAppend) {
      requireLine(line, columns, "history line");
    }
    for (RowChange change : screenRows) {
      requireLine(change.line(), columns, "screen line");
    }
    if (type == Kind.RESET) {
      if (baseVersion != null) {
        throw new IllegalArgumentException("RESET must not declare baseVersion");
      }
      if (historyTrim != 0) {
        throw new IllegalArgumentException("RESET must not trim history");
      }
      if (historyAppend.size() != history) {
        throw new IllegalArgumentException(
            "RESET must append exactly history lines: " + historyAppend.size());
      }
      if (screenRows.size() != rows) {
        throw new IllegalArgumentException(
            "RESET must replace every screen row exactly once: " + screenRows.size());
      }
    } else {
      if (baseVersion == null || baseVersion < 1 || baseVersion > TerminalLimits.MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("PATCH must declare a positive safe baseVersion");
      }
      if (version <= baseVersion) {
        throw new IllegalArgumentException("PATCH version must be greater than baseVersion");
      }
      if (historyAppend.size() > history) {
        throw new IllegalArgumentException(
            "PATCH history append exceeds declared history: " + historyAppend.size());
      }
      if (screenRows.size() > rows) {
        throw new IllegalArgumentException(
            "PATCH screen replacements exceed rows: " + screenRows.size());
      }
    }
    requireUniqueLineIds(historyAppend, screenRows);
    boolean[] replacedRows = new boolean[rows];
    for (RowChange change : screenRows) {
      int row = change.row();
      if (row < 0 || row >= rows) {
        throw new IllegalArgumentException("screen row out of range: " + row);
      }
      if (replacedRows[row]) {
        throw new IllegalArgumentException("duplicate screen row replacement: " + row);
      }
      replacedRows[row] = true;
    }
    if (alternate) {
      if (history != 0 || historyTrim != 0 || !historyAppend.isEmpty()) {
        throw new IllegalArgumentException("alternate screen must not carry history");
      }
    }
  }

  /** 一行恰好 {@link #columns} 个槽；EMPTY 使用 code 0，DWC 使用 code 0xe000，UNIT 保留原始 UTF-16 单位。 */
  private static void requireLine(TerminalView.Line line, int columns, String what) {
    Objects.requireNonNull(line, what);
    if (line.id() < 1 || line.id() > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(what + " id out of range: " + line.id());
    }
    if (line.slots().size() != columns) {
      throw new IllegalArgumentException(what + " must have exactly " + columns + " slots");
    }
    for (TerminalView.Slot slot : line.slots()) {
      if (slot.kind() == TerminalView.SlotKind.EMPTY && slot.code() != 0) {
        throw new IllegalArgumentException(what + " empty slot must use code 0");
      }
      if (slot.kind() == TerminalView.SlotKind.DWC && slot.code() != 0xe000) {
        throw new IllegalArgumentException(what + " dwc slot must use code 0xe000");
      }
    }
  }

  /** 同一消息内历史与屏幕来自不同实际行，因此行 id 必须整体唯一。 */
  private static void requireUniqueLineIds(
      List<TerminalView.Line> historyAppend, List<RowChange> screenRows) {
    Set<Long> ids = new HashSet<>();
    for (TerminalView.Line line : historyAppend) {
      if (!ids.add(line.id())) {
        throw new IllegalArgumentException("duplicate line id: " + line.id());
      }
    }
    for (RowChange change : screenRows) {
      if (!ids.add(change.line().id())) {
        throw new IllegalArgumentException("duplicate line id: " + change.line().id());
      }
    }
  }
}
