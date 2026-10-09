package fun.fengwk.kkstudio.harness.daemon.coding;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 在 Java 原生搜索的遍历、读取与匹配循环中统一检查 deadline 和 cancellation。 */
final class SearchControl {

  private final BooleanSupplier cancelled;
  private final long startedNanos;
  private final long timeoutNanos;
  private final long timeoutMillis;
  private final String capabilityName;
  private final LongSupplier nanoTime;

  /** 没有 deadline：{@link #check()} 只检查中断与取消。 */
  private static final long NO_DEADLINE_NANOS = Long.MAX_VALUE;

  SearchControl(
      Duration timeout, BooleanSupplier cancelled, String capabilityName, LongSupplier nanoTime) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    this.capabilityName = Objects.requireNonNull(capabilityName, "capabilityName");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    startedNanos = nanoTime.getAsLong();
    // timeout 为 0 表示没有执行 deadline：绝不能退化为「立即超时」。
    timeoutNanos = timeout.isZero() ? NO_DEADLINE_NANOS : timeout.toNanos();
    timeoutMillis = timeout.toMillis();
  }

  static SearchControl start(
      Duration timeout, AbstractCodingCapability.Execution execution, String capabilityName) {
    Objects.requireNonNull(execution, "execution");
    return new SearchControl(timeout, execution::isCancelled, capabilityName, System::nanoTime);
  }

  void check() throws InterruptedException {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
      throw new InterruptedException();
    }
    if (timeoutNanos == NO_DEADLINE_NANOS) {
      return;
    }
    if (nanoTime.getAsLong() - startedNanos >= timeoutNanos) {
      throw new ToolRunFailureException(
          capabilityName + " timed out after " + timeoutMillis + " milliseconds");
    }
  }

  /**
   * {@link #check()} 的无 checked 异常版本：取消转换为 {@link CancelledException}，用于无法声明 {@link
   * InterruptedException} 的回调（正则引擎在 {@link CharSequence#charAt(int)} 中读取输入时就是这种情形）。超时仍以 {@link
   * IllegalArgumentException} 暴露。
   */
  void checkUnchecked() {
    try {
      check();
    } catch (InterruptedException error) {
      throw new CancelledException(error);
    }
  }

  /**
   * 包装正则输入，使引擎在逐个字符扫描时周期性执行 {@link #check()}。
   *
   * <p>一次 {@code Matcher.find()} 可能遍历整个输入而没有匹配（例如大体量文件的单次无匹配扫描），只在两次匹配之间检查控制位无法及时响应 deadline
   * 与取消。包装后的 {@link CharSequence} 让取消/超时在扫描过程中可执行，而不是等引擎自己让出。
   */
  CharSequence deadlineChecked(CharSequence source) {
    Objects.requireNonNull(source, "source");
    return new DeadlineCheckedCharSequence(source, this);
  }

  /** 取消在无法抛出 checked 异常的调用点上的非受检载体；调用方在适当时机把它还原为 {@link InterruptedException}。 */
  static final class CancelledException extends RuntimeException {

    private CancelledException(InterruptedException cause) {
      super(cause);
    }

    InterruptedException interruption() {
      return (InterruptedException) getCause();
    }
  }

  private static final class DeadlineCheckedCharSequence implements CharSequence {

    /** 每读取这么多字符检查一次：远小于任何输入，也不至于让检查本身成为热点。 */
    private static final int CHECK_INTERVAL_MASK = 0xFFF;

    private final CharSequence source;
    private final SearchControl control;
    private int accesses;

    private DeadlineCheckedCharSequence(CharSequence source, SearchControl control) {
      this.source = source;
      this.control = control;
    }

    @Override
    public int length() {
      return source.length();
    }

    @Override
    public char charAt(int index) {
      if ((accesses++ & CHECK_INTERVAL_MASK) == 0) {
        control.checkUnchecked();
      }
      return source.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return source.subSequence(start, end);
    }

    @Override
    public String toString() {
      return source.toString();
    }
  }
}
