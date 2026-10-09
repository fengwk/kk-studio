package fun.fengwk.kkstudio.harness.daemon.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.Kind;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate.RowChange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 单个观察流的画面更新生成器：把连续捕获的 {@link TerminalView} 归约为有界 RESET/PATCH 与恰好一个在途消息。
 *
 * <p>状态只由调用方串行访问（单个状态 owner，不限定为 VT owner），不加锁也不自带 executor、时钟、配置或发送出口。它只保存一份已确认基线 view、一个在途 view
 * 与其版本，不保留 deque、未确认 delta 链或「最新待发」字段：有在途时 {@link #offer(TerminalView)} 直接返回空，最新画面由调用方在归还额度后再次提供。
 *
 * <p>新流的首条消息是 {version=1} 的 RESET；在精确匹配 {@code streamId + inflightVersion} 的 ACK 到来前不存在观察基线。匹配 ACK
 * 原子提升在途 view 为基线并归还额度；不匹配或重复 ACK 返回 {@code false} 且不推进、不清理任何状态。RESET 与 PATCH 都由同一个 {@link
 * TerminalViewUpdate} 规范构造器校验，非法画面或版本耗尽都在发布时显式失败。
 *
 * <p>增量判定只使用实际行身份：尺寸或主/备用屏变化、两版画面没有任何共享行 id、历史重叠无法用 id + 整行相等证明时退回 RESET；仅 cursor/mode 变化也产生
 * metadata-only PATCH，完全相同画面返回空且不推进版本。
 */
public final class TerminalViewStream {

  private final UUID terminalId;
  private final UUID streamId;

  private TerminalView appliedBaseline;
  private long appliedVersion;
  private TerminalView inflightView;
  private long inflightVersion;
  private boolean closed;

  /**
   * 绑定一个终端与显示流身份。
   *
   * @param terminalId 终端身份，随 shell 生命周期稳定
   * @param streamId 本次显示同步流身份，新流必须与旧流不同
   */
  public TerminalViewStream(UUID terminalId, UUID streamId) {
    this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
    this.streamId = Objects.requireNonNull(streamId, "streamId");
  }

  /**
   * 用最新捕获的画面生成下一条更新。
   *
   * <p>流已关闭或已有在途更新时返回 {@link Optional#empty()}，不校验也不保存本次画面。已确认基线与新画面完全相同也返回空且不推进版本；只有真正
   * 变化才推进一个版本。成功时返回的更新进入在途状态，等待 {@link #applied(UUID, long)}。
   *
   * @param view 最新捕获的深不可变画面
   * @return 需要发送的更新，或无需发送时为空
   * @throws IllegalArgumentException 画面形状或输入模式版本不满足协议约束
   * @throws IllegalStateException 版本已耗尽（不会退化为溢出回绕）
   */
  public Optional<TerminalViewUpdate> offer(TerminalView view) {
    Objects.requireNonNull(view, "view");
    if (closed || inflightView != null) {
      return Optional.empty();
    }
    requireValidView(view);
    if (appliedVersion >= TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalStateException("terminal view stream version exhausted");
    }
    long version = appliedVersion + 1;
    TerminalViewUpdate update =
        appliedBaseline == null ? reset(view, version) : delta(appliedBaseline, view, version);
    if (update == null) {
      return Optional.empty();
    }
    inflightView = view;
    inflightVersion = version;
    return Optional.of(update);
  }

  /**
   * 确认一条在途更新已完整应用/绘制。
   *
   * <p>只有 {@code streamId} 与版本都精确匹配当前在途更新才推进：基线被提升为在途画面、额度归还，返回 {@code true}。不匹配、重复或流已关闭一律返回 {@code
   * false}，不改动基线、版本与在途状态。
   *
   * @param streamId 观察到的流身份
   * @param version 观察到的已应用版本
   * @return 是否发生推进
   */
  public boolean applied(UUID streamId, long version) {
    if (closed || inflightView == null || version != inflightVersion) {
      return false;
    }
    if (!this.streamId.equals(streamId)) {
      return false;
    }
    appliedBaseline = inflightView;
    appliedVersion = inflightVersion;
    inflightView = null;
    inflightVersion = 0L;
    return true;
  }

  /** 最近一次被确认的应用版本；尚无确认基线时为 0。 */
  public long lastAppliedVersion() {
    return appliedVersion;
  }

  /** 是否存在等待确认的在途更新。 */
  public boolean hasInFlight() {
    return inflightView != null;
  }

  /** 是否已存在任何被确认的基线（新流在首个匹配 ACK 前为 {@code false}）。 */
  public boolean isApplied() {
    return appliedBaseline != null;
  }

  /**
   * 关闭本流；幂等。释放基线/在途画面引用并归还额度，但保留已确认版本号作为历史事实。关闭后 {@link #offer(TerminalView)} 恒返回空，{@link
   * #applied(UUID, long)} 恒为 {@code false}，{@link #isApplied()} 与 {@link #hasInFlight()} 恒为 {@code
   * false}。
   */
  public void close() {
    closed = true;
    appliedBaseline = null;
    inflightView = null;
    inflightVersion = 0L;
  }

  /** 已有基线时按增量规则生成 PATCH 或退回 RESET；完全相同返回 {@code null}。 */
  private TerminalViewUpdate delta(TerminalView base, TerminalView view, long version) {
    if (view.equals(base)) {
      return null;
    }
    // 输入模式版本单调：任何下降都是非法输入，优先于尺寸/主备屏回退为 RESET 的分支。
    if (view.inputModeRevision() < base.inputModeRevision()) {
      throw new IllegalArgumentException(
          "input mode revision decreased: "
              + view.inputModeRevision()
              + " < "
              + base.inputModeRevision());
    }
    if (view.columns() != base.columns()
        || view.rows() != base.rows()
        || view.alternate() != base.alternate()) {
      return reset(view, version);
    }
    if (!sharesLineId(base, view)) {
      // 无任何共享行 id：reflow 或主/备用屏切换往返，即使尺寸相同也不能当增量。
      return reset(view, version);
    }
    List<TerminalView.Line> oldHistory = historyOf(base);
    List<TerminalView.Line> newHistory = historyOf(view);
    int overlap = historyOverlap(oldHistory, newHistory);
    if (overlap < 0) {
      return reset(view, version);
    }
    List<TerminalView.Line> append = newHistory.subList(overlap, newHistory.size());
    return new TerminalViewUpdate(
        Kind.PATCH,
        terminalId,
        streamId,
        appliedVersion,
        version,
        view.columns(),
        view.rows(),
        view.alternate(),
        view.history(),
        view.cursorX(),
        view.cursorY(),
        view.cursorVisible(),
        view.cursorShape(),
        view.inputModeRevision(),
        view.inputModes(),
        oldHistory.size() - overlap,
        append,
        changedRows(base, view));
  }

  /** RESET：替换整屏与全部历史，historyTrim 恒为 0。 */
  private TerminalViewUpdate reset(TerminalView view, long version) {
    return new TerminalViewUpdate(
        Kind.RESET,
        terminalId,
        streamId,
        null,
        version,
        view.columns(),
        view.rows(),
        view.alternate(),
        view.history(),
        view.cursorX(),
        view.cursorY(),
        view.cursorVisible(),
        view.cursorShape(),
        view.inputModeRevision(),
        view.inputModes(),
        0,
        historyOf(view),
        allRows(view));
  }

  /**
   * 返回保留的旧历史行数，无法用 id + 整行相等证明时返回 {@code -1}。
   *
   * <p>以新历史首行 id 在旧历史中的位置确定裁剪点，再逐行验证旧历史后缀与新历史前缀整行相等（含 id、wrapped 与数值槽）；旧历史为空时全部追加，
   * 旧历史非空而新历史为空、找不到重叠或保留行被改写都视为结构不连续。
   */
  private static int historyOverlap(
      List<TerminalView.Line> oldHistory, List<TerminalView.Line> newHistory) {
    if (oldHistory.isEmpty()) {
      return 0;
    }
    if (newHistory.isEmpty()) {
      return -1;
    }
    int start = indexOfId(oldHistory, newHistory.get(0).id());
    if (start < 0) {
      return -1;
    }
    int retained = oldHistory.size() - start;
    if (newHistory.size() < retained) {
      return -1;
    }
    for (int i = 0; i < retained; i++) {
      if (!newHistory.get(i).equals(oldHistory.get(start + i))) {
        return -1;
      }
    }
    return retained;
  }

  private static int indexOfId(List<TerminalView.Line> lines, long id) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).id() == id) {
        return i;
      }
    }
    return -1;
  }

  /** 两版画面的全部活动历史与屏幕是否有任何共享行 id。 */
  private static boolean sharesLineId(TerminalView base, TerminalView view) {
    Set<Long> ids = new HashSet<>();
    for (TerminalView.Line line : base.lines()) {
      ids.add(line.id());
    }
    for (TerminalView.Line line : view.lines()) {
      if (ids.contains(line.id())) {
        return true;
      }
    }
    return false;
  }

  /** 活动屏中有变化的行，只有这些行进入 PATCH。 */
  private static List<RowChange> changedRows(TerminalView base, TerminalView view) {
    List<RowChange> changes = new ArrayList<>();
    for (int row = 0; row < view.rows(); row++) {
      TerminalView.Line after = view.lines().get(view.history() + row);
      TerminalView.Line before = base.lines().get(base.history() + row);
      if (!after.equals(before)) {
        changes.add(new RowChange(row, after));
      }
    }
    return changes;
  }

  private static List<RowChange> allRows(TerminalView view) {
    List<RowChange> rows = new ArrayList<>(view.rows());
    for (int row = 0; row < view.rows(); row++) {
      rows.add(new RowChange(row, view.lines().get(view.history() + row)));
    }
    return rows;
  }

  private static List<TerminalView.Line> historyOf(TerminalView view) {
    return view.lines().subList(0, view.history());
  }

  /**
   * 校验捕获画面的形状，保证可安全派生规范更新且不静默截断。
   *
   * <p>{@link TerminalView} 自身只校验行 id 为正与槽 code 为 UTF-16 单位；尺寸/历史上限、行数、光标、revision、备用屏与历史一致性以及整屏行
   * id 唯一性在这里按 {@link TerminalLimits} 显式校验。消息级约束（baseVersion、整行槽数、槽 kind/code 约定等）由 {@link
   * TerminalViewUpdate} 规范构造器在发布时强制。
   */
  private static void requireValidView(TerminalView view) {
    int columns = view.columns();
    int rows = view.rows();
    int history = view.history();
    if (columns < TerminalLimits.MIN_COLUMNS || columns > TerminalLimits.MAX_COLUMNS) {
      throw new IllegalArgumentException("view columns out of range: " + columns);
    }
    if (rows < TerminalLimits.MIN_ROWS || rows > TerminalLimits.MAX_ROWS) {
      throw new IllegalArgumentException("view rows out of range: " + rows);
    }
    if (history < 0 || history > TerminalLimits.MAX_HISTORY_LINES) {
      throw new IllegalArgumentException("view history out of range: " + history);
    }
    if (view.lines().size() != history + rows) {
      throw new IllegalArgumentException(
          "view must hold exactly history + rows lines: " + view.lines().size());
    }
    if (view.cursorX() < 0 || view.cursorX() > columns) {
      throw new IllegalArgumentException("view cursorX out of range: " + view.cursorX());
    }
    if (view.cursorY() < 0 || view.cursorY() > rows - 1) {
      throw new IllegalArgumentException("view cursorY out of range: " + view.cursorY());
    }
    if (view.inputModeRevision() < 1
        || view.inputModeRevision() > TerminalLimits.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(
          "view inputModeRevision out of range: " + view.inputModeRevision());
    }
    if (view.alternate() && history != 0) {
      throw new IllegalArgumentException("alternate view must not carry history: " + history);
    }
    Set<Long> ids = new HashSet<>();
    for (TerminalView.Line line : view.lines()) {
      if (line.id() < 1 || line.id() > TerminalLimits.MAX_SAFE_INTEGER) {
        throw new IllegalArgumentException("view line id out of range: " + line.id());
      }
      if (line.slots().size() != columns) {
        throw new IllegalArgumentException(
            "view line must have exactly " + columns + " slots: " + line.slots().size());
      }
      if (!ids.add(line.id())) {
        throw new IllegalArgumentException("duplicate view line id: " + line.id());
      }
    }
  }
}
