package fun.fengwk.kkstudio.harness.mcp;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClientListener;

import java.io.IOException;

/**
 * 调用级取消令牌与「在途请求派发」之间的汇合点，同时充当传输层的派发闸门（{@link McpDispatchGate}）。
 *
 * <p>取消可能发生在任意时刻，因此本类覆盖两个方向的交错，且不依赖任何时序假设：
 *
 * <ul>
 *   <li><b>取消先于派发</b>：请求绝不写出，本地等待立即以取消结束；
 *   <li><b>取消晚于派发</b>：结束该 request 的本地等待，并按协议发送 {@code notifications/cancelled}。
 * </ul>
 *
 * <p>关键不变式：取消判定与在途登记由 {@link Binding#dispatch} 在同一个监视器临界区内完成，因此不存在「取消判定时看不到在途请求、
 * 但请求随后才被登记」的窗口。取消失败绝不关闭共享 client，同版本其它并发调用不受影响。
 *
 * <p>状态只存在于一次性 {@link Binding} 中：绑定在发起操作的线程上激活、在该次操作结束时废弃，因此不随调用次数积累任何共享状态。传输层派发闸门总能提供实际 request
 * id；工具调用的 SDK listener 回调只用于在派发之前尽早记录该 id。
 */
final class McpRequestBindings implements McpDispatchGate, McpClientListener {

  private volatile McpRequestAborter aborter;
  private final ThreadLocal<Binding> active = new ThreadLocal<>();

  /** 先创建汇合点、再创建传输（传输需要它作为派发闸门），最后通过 {@link #bindAborter} 装配中止通道。 */
  McpRequestBindings() {}

  /** 装配期一次性绑定中止通道；{@code null} 表示该传输无需协议取消通道（例如关闭 per-request SSE 流即可取消的 HTTP）。 */
  void bindAborter(McpRequestAborter aborter) {
    this.aborter = aborter;
  }

  /** 创建一次操作的绑定，并立即挂到取消令牌上；可在调用线程创建，随后在 worker 线程 {@link #activate}。 */
  Binding create(McpCancellationToken token) {
    Binding binding = new Binding(this);
    token.onCancel(() -> binding.abort("caller cancelled"));
    return binding;
  }

  /** 在真正发起 SDK 操作的线程上激活绑定，使传输层与监听回调能定位到本次操作。 */
  void activate(Binding binding) {
    active.set(binding);
  }

  /** 结束当前线程的绑定，避免线程复用导致状态串扰。 */
  void deactivate() {
    active.remove();
  }

  @Override
  public boolean dispatchOrAbort(long requestId, CheckedDispatchAction action) throws IOException {
    Binding binding = active.get();
    if (binding == null) {
      // 初始化握手等非调用级请求没有取消意图，直接执行派发写出动作。
      action.run();
      return true;
    }
    return binding.dispatch(requestId, action);
  }

  /**
   * 在 SDK 回调中把该次调用的真实 request id 告知绑定。
   *
   * <p>SDK 在写出请求之前回调本方法，而「是否已被取消」的判定与在途登记由传输层的 {@link #dispatchOrAbort} 原子完成，因此这里只登记 request id，
   * 不在此处发送取消通知：取消先到则请求根本不会写出。
   */
  @Override
  public void beforeExecuteTool(McpCallContext context) {
    Binding binding = active.get();
    if (binding == null || context == null || context.message() == null) {
      return;
    }
    Long requestId = context.message().getId();
    if (requestId != null) {
      binding.prepare(requestId);
    }
  }

  /** 单次操作的派发/取消状态机；所有状态都在本对象监视器下读写。 */
  static final class Binding {

    private final McpRequestBindings owner;
    private long requestId = -1L;
    private boolean aborted;
    private boolean dispatched;
    private boolean abortNotified;
    private String abortReason;

    private Binding(McpRequestBindings owner) {
      this.owner = owner;
    }

    /** 记录即将发起的 request id；此时请求尚未登记，取消通知一律等到 {@link #dispatch} 之后才可能发出。 */
    synchronized void prepare(long boundRequestId) {
      requestId = boundRequestId;
    }

    /**
     * 传输写出请求之前，原子完成「是否已被取消」的判定、在途登记与底层写出。
     *
     * <p>临界区完整包裹派发动作（登记 + 写出）：取消方要么在请求写出前取得监视器使派发被放弃（原请求完全不发出）， 要么等派发动作把原请求完整写出到管道后再取得监视器发送 {@code
     * notifications/cancelled}。 消除「派发释放锁后但在写出前，取消通知抢先到达服务端」的颠倒竞态。
     */
    synchronized boolean dispatch(long boundRequestId, CheckedDispatchAction action)
        throws IOException {
      if (aborted) {
        return false;
      }
      requestId = boundRequestId;
      action.run();
      dispatched = true;
      return true;
    }

    /** 中止本次操作对应的在途请求：只影响该 request，不影响连接与其它并发请求。 */
    void abort(String reason) {
      long targetId;
      synchronized (this) {
        aborted = true;
        abortReason = reason;
        if (!dispatched || abortNotified) {
          // 尚未派发：派发判定会拦住请求本身，此处不得发送取消通知；已通知过则保持幂等。
          return;
        }
        abortNotified = true;
        targetId = requestId;
      }
      McpRequestAborter target = owner.aborter;
      if (target != null) {
        target.abort(targetId, abortReason == null ? "cancelled" : abortReason);
      }
    }
  }
}
