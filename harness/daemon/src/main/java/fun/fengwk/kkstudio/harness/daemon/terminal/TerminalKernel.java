package fun.fengwk.kkstudio.harness.daemon.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDataStream;
import com.jediterm.terminal.TerminalOutputStream;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 单一 owner 的 bounded headless VT 内核，唯一实现是 JediTerm 3.76。
 *
 * <p>内核只接受调用方拥有的 {@link ExecutorService}，在其上运行唯一的 owner 任务完成全部 JediTerm 创建、解释、resize、key 编码与画面捕获；
 * 内核自身不创建线程池、static executor 或直接启动虚拟线程。UTF-8 分块解码保留跨 chunk 尾字节，非法字节按终端文本惯例替换，不做 VT 解析。
 *
 * <p>一个有界 FIFO（{@link #EVENT_QUEUE_CAPACITY}）承载输入与有序控制：数据流在空读点执行控制，因此 CSI/OSC/UTF-8 未完成时依然可
 * snapshot/resize/close；只有真正 close 才在空读点发 EOF，临时空队列绝不伪造 EOF。单次 {@code emulator.next()} 的 UTF-16
 * 消费上限为 {@link #READ_BUDGET_UNITS}，超限显式失败而不丢弃前缀继续。DA/DSR/OSC 回应在同一 owner 生成，只进入构造时注入的非阻塞 {@link
 * Consumer}；该回调抛错则内核明确失败，且任何错误信息都不携带屏幕、输入或出站数据。
 *
 * <p>各 public 异步方法返回的 future 只承诺对应事件被 owner 有序受理：{@code feed} 完成不代表完整 VT 序列已经解释，后续 {@code snapshot}
 * 的 FIFO 屏障才确定此前可解释数据已完成；{@code close} 之后所有未决 future 一律明确终结。内核不关闭调用方注入的 executor。
 */
public final class TerminalKernel implements AutoCloseable {

  /** 单次 {@code feed} 允许的最大输入字节数；调用时即复制，不持有外部可变数组。 */
  public static final int MAX_INPUT_CHUNK_BYTES = 4096;

  /** 输入/控制 FIFO 容量；满时明确拒绝，不吞消息也不无限等待。 */
  public static final int EVENT_QUEUE_CAPACITY = 128;

  /** 单次 {@code emulator.next()} 消耗的 UTF-16 单位上限。 */
  public static final int READ_BUDGET_UNITS = 65536;

  private static final long CLOSE_TIMEOUT_SECONDS = 5L;
  private static final int DECODE_BUFFER_BYTES = MAX_INPUT_CHUNK_BYTES * 2;
  private static final int DECODE_BUFFER_CHARS = MAX_INPUT_CHUNK_BYTES * 2;

  private final Future<?> ownerTask;
  private final Consumer<byte[]> responder;
  private final BlockingQueue<Event> events = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();

  private volatile boolean closing;
  private volatile Throwable failure;
  private Thread ownerThread;

  // 以下状态只由 owner 任务读写。
  private CharsetDecoder decoder;
  private ByteBuffer pendingBytes;
  private ArrayDeque<Character> chars;
  private JediTerminal terminal;
  private TerminalTextBuffer buffer;
  private HeadlessTerminalDisplay display;
  private TerminalSnapshotProjector projector;
  private JediEmulator emulator;
  private int consumedUnits;
  private Throwable outputFailure;
  private Throwable dataStreamFailure;
  private long inputModeRevision = 1L;
  private InputState lastInputState;

  /**
   * 在调用方的 executor 上启动 owner 任务。
   *
   * @param columns 初始列数，至少 5 列（JediTerm 的公开尺寸下限）
   * @param rows 初始行数，至少 2 行
   * @param maxHistoryLines 真实 JediTerm 缓冲允许的历史行数，不得为负
   * @param executor 调用方拥有的 executor，内核不关闭它
   * @param responder 非阻塞的 DA/DSR/OSC 出站回调，只在 owner 线程调用
   */
  public TerminalKernel(
      int columns,
      int rows,
      int maxHistoryLines,
      ExecutorService executor,
      Consumer<byte[]> responder) {
    requireSize(columns, rows);
    if (maxHistoryLines < 0) {
      throw new IllegalArgumentException("maxHistoryLines must not be negative");
    }
    Objects.requireNonNull(executor, "executor");
    this.responder = Objects.requireNonNull(responder, "responder");
    ownerTask = executor.submit(() -> run(columns, rows, maxHistoryLines));
  }

  /** 提交一个 UTF-8 输入 chunk；完成仅表示 owner 受理该 chunk。 */
  public CompletableFuture<Void> feed(byte[] data) {
    Objects.requireNonNull(data, "data");
    if (data.length > MAX_INPUT_CHUNK_BYTES) {
      throw new IllegalArgumentException(
          "terminal input chunk exceeds " + MAX_INPUT_CHUNK_BYTES + " bytes");
    }
    CompletableFuture<Void> accepted = new CompletableFuture<>();
    return offer(new Input(data.clone(), accepted), accepted);
  }

  /** 捕获当前画面的不可变投影。 */
  public CompletableFuture<TerminalView> snapshot() {
    CompletableFuture<TerminalView> result = new CompletableFuture<>();
    return offer(new Control<>(this::capture, result), result);
  }

  /** 请求权威尺寸变更；无效尺寸在提交前同步拒绝。 */
  public CompletableFuture<Void> resize(int columns, int rows) {
    requireSize(columns, rows);
    CompletableFuture<Void> result = new CompletableFuture<>();
    return offer(
        new Control<>(
            () -> {
              resizeTo(columns, rows);
              return null;
            },
            result),
        result);
  }

  /** 用当前输入模式编码按键；未知键返回 {@code null}（同一 owner 执行）。 */
  public CompletableFuture<byte[]> encodeKey(int keyCode, int modifiers) {
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    return offer(new Control<>(() -> terminal.getCodeForKey(keyCode, modifiers), result), result);
  }

  /** 内核停止的只读完成信号；异常完成明确报告解释或出站失败。 */
  public CompletableFuture<Void> termination() {
    return terminated.copy();
  }

  /** 关闭优先于待决事件，并有界等待 owner 释放；绝不关闭调用方 executor。 */
  @Override
  public void close() {
    Thread owner;
    synchronized (this) {
      closing = true;
      if (terminated.isDone()) {
        return;
      }
      owner = ownerThread;
      if (owner == null) {
        ownerTask.cancel(false);
        finish(null);
        return;
      }
      // 空队列上的 owner 需要唤醒；满队列已有事件，关闭标记不占队列预算。
      events.offer(new Shutdown());
    }
    if (Thread.currentThread() == owner) {
      return;
    }
    awaitTermination(owner);
  }

  private <T> CompletableFuture<T> offer(Event event, CompletableFuture<T> result) {
    synchronized (this) {
      if (closing || terminated.isDone()) {
        return CompletableFuture.failedFuture(terminationFailure());
      }
      if (!events.offer(event)) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("terminal kernel event queue is full"));
      }
      return result;
    }
  }

  private void run(int columns, int rows, int maxHistoryLines) {
    synchronized (this) {
      if (closing) {
        return;
      }
      ownerThread = Thread.currentThread();
    }
    Throwable error = null;
    try {
      init(columns, rows, maxHistoryLines);
      lastInputState = inputState();
      while (emulator.hasNext()) {
        resetReadBudget();
        emulator.next();
        if (outputFailure != null) {
          // 上游某些解析分支吞掉异常，owner 边界必须明确传播失败。
          throw outputFailure;
        }
        if (dataStreamFailure != null) {
          // 上游捕获数据流异常时，预算失败仍必须终止内核。
          throw dataStreamFailure;
        }
        refreshRevision();
      }
    } catch (Throwable thrown) {
      error = thrown;
    } finally {
      finish(error);
    }
  }

  private synchronized void finish(Throwable error) {
    closing = true;
    failure = error;
    Event event;
    while ((event = events.poll()) != null) {
      event.fail(terminationFailure());
    }
    if (error != null) {
      terminated.completeExceptionally(error);
    } else {
      terminated.complete(null);
    }
  }

  private IllegalStateException terminationFailure() {
    Throwable cause = failure;
    if (cause != null) {
      return new IllegalStateException("terminal kernel terminated", cause);
    }
    return new IllegalStateException("terminal kernel is closed");
  }

  private void init(int columns, int rows, int maxHistoryLines) {
    decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    pendingBytes = ByteBuffer.allocate(DECODE_BUFFER_BYTES);
    chars = new ArrayDeque<>();
    StyleState styleState = new StyleState();
    buffer = new TerminalTextBuffer(columns, rows, styleState, maxHistoryLines);
    display = new HeadlessTerminalDisplay();
    projector = new TerminalSnapshotProjector();
    terminal = new JediTerminal(display, buffer, styleState);
    terminal.setTerminalOutput(new KernelOutputStream());
    emulator = new JediEmulator(new KernelDataStream(), terminal);
    consumedUnits = 0;
    outputFailure = null;
    dataStreamFailure = null;
  }

  private void awaitTermination(Thread owner) {
    try {
      terminated.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("terminal kernel close interrupted");
    } catch (ExecutionException failure) {
      // 已停止；异常由 termination 与未决操作报告。
    } catch (TimeoutException timeout) {
      interruptOwner(owner);
    }
  }

  private void interruptOwner(Thread owner) {
    owner.interrupt();
    try {
      terminated.get(1L, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("terminal kernel close interrupted");
    } catch (ExecutionException failure) {
      // 已停止；异常由 termination 与未决操作报告。
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("terminal kernel owner did not stop");
    }
  }

  private TerminalView capture() {
    return projector.project(terminal, buffer, display, inputModeRevision);
  }

  private void resizeTo(int columns, int rows) {
    terminal.resize(new TermSize(columns, rows), RequestOrigin.User);
    refreshRevision();
  }

  private static void requireSize(int columns, int rows) {
    TermSize requested = new TermSize(columns, rows);
    if (!requested.equals(JediTerminal.ensureTermMinimumSize(requested))) {
      throw new IllegalArgumentException("terminal size must be at least 5 columns and 2 rows");
    }
  }

  private void refreshRevision() {
    buffer.lock();
    try {
      projector.resetIfChanged(buffer);
      InputState state = inputState();
      if (!state.equals(lastInputState)) {
        lastInputState = state;
        inputModeRevision++;
      }
    } finally {
      buffer.unlock();
    }
  }

  private InputState inputState() {
    return new InputState(
        terminal.getTerminalWidth(),
        terminal.getTerminalHeight(),
        TerminalSnapshotProjector.inputModes(terminal, display));
  }

  private void dispatch(Event event) {
    try {
      if (event instanceof Input input) {
        decode(input.data());
        input.accepted().complete(null);
      } else if (event instanceof Control<?> control) {
        execute(control);
      }
    } catch (Throwable error) {
      event.fail(error);
      dataStreamFailure = error;
      throw unchecked(error);
    }
  }

  private <T> void execute(Control<T> control) {
    try {
      control.result().complete(control.action().call());
    } catch (Exception error) {
      throw unchecked(error);
    }
  }

  private static RuntimeException unchecked(Throwable error) {
    if (error instanceof RuntimeException runtime) {
      return runtime;
    }
    if (error instanceof Error unhandled) {
      throw unhandled;
    }
    return new IllegalStateException(error);
  }

  private void decode(byte[] data) {
    pendingBytes.put(data);
    pendingBytes.flip();
    CharBuffer decoded = CharBuffer.allocate(DECODE_BUFFER_CHARS);
    // REPLACE 不产生编码错误；缓冲容量覆盖一个 chunk 与最多三个尾字节。
    decoder.decode(pendingBytes, decoded, false);
    pendingBytes.compact();
    decoded.flip();
    while (decoded.hasRemaining()) {
      chars.addLast(decoded.get());
    }
  }

  private void resetReadBudget() {
    consumedUnits = 0;
  }

  private void account(int units) throws IOException {
    consumedUnits += units;
    if (consumedUnits > READ_BUDGET_UNITS) {
      // 上游可能吞掉数据流异常，owner 边界仍需报告预算失败。
      IOException exceeded = new IOException("terminal read budget exceeded");
      dataStreamFailure = exceeded;
      throw exceeded;
    }
  }

  private Event takeEvent() throws IOException {
    try {
      return events.take();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("terminal kernel owner interrupted", interrupted);
    }
  }

  private interface Event {

    void fail(Throwable error);
  }

  private record InputState(int columns, int rows, TerminalView.InputModes modes) {}

  private record Input(byte[] data, CompletableFuture<Void> accepted) implements Event {

    @Override
    public void fail(Throwable error) {
      accepted.completeExceptionally(error);
    }
  }

  private record Control<T>(Callable<T> action, CompletableFuture<T> result) implements Event {

    @Override
    public void fail(Throwable error) {
      result.completeExceptionally(error);
    }
  }

  private record Shutdown() implements Event {

    @Override
    public void fail(Throwable error) {
      // 关闭哨兵本身没有待决 future。
    }
  }

  private final class KernelDataStream implements TerminalDataStream {

    @Override
    public char getChar() throws IOException {
      if (closing) {
        throw new EOF();
      }
      while (chars.isEmpty()) {
        Event event = takeEvent();
        if (closing) {
          event.fail(terminationFailure());
          throw new EOF();
        }
        dispatch(event);
      }
      char next = chars.removeFirst();
      account(1);
      return next;
    }

    @Override
    public void pushChar(char c) {
      chars.addFirst(c);
    }

    @Override
    public void pushBackBuffer(char[] data, int length) {
      for (int index = length - 1; index >= 0; index--) {
        chars.addFirst(data[index]);
      }
    }

    @Override
    public String readNonControlCharacters(int maxChars) throws IOException {
      StringBuilder result = new StringBuilder();
      while (result.length() < maxChars && !chars.isEmpty()) {
        char next = chars.peekFirst();
        if (next < 32) {
          break;
        }
        chars.removeFirst();
        account(1);
        result.append(next);
      }
      return result.toString();
    }

    @Override
    public boolean isEmpty() {
      return chars.isEmpty();
    }
  }

  private final class KernelOutputStream implements TerminalOutputStream {

    @Override
    public void sendBytes(byte[] bytes, boolean prepend) {
      if (bytes != null) {
        emit(bytes);
      }
    }

    @Override
    public void sendString(String string, boolean prepend) {
      emit(string.getBytes(StandardCharsets.UTF_8));
    }

    private void emit(byte[] data) {
      if (outputFailure != null) {
        return;
      }
      try {
        responder.accept(data);
      } catch (Throwable error) {
        // 外部回调异常可能含终端内容；仅暴露固定失败原因。
        outputFailure = new IllegalStateException("terminal response delivery failed");
      }
    }
  }
}
