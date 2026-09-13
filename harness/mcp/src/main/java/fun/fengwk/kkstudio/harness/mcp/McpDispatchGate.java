package fun.fengwk.kkstudio.harness.mcp;

import java.io.IOException;

/**
 * 派发前取消判定：让共享单通道传输在写出请求之前拦住「已取消但尚未登记/写出」的窗口。
 *
 * <p>调用级绑定（{@link McpRequestBindings}）在 SDK 监听回调中先拿到真实 request id，随后 SDK 才把请求交给传输写出；两者之间存在必然的时间窗口。
 * 若传输只依赖「按 request id 中止」，落在该窗口内的取消会被静默丢弃，请求仍然照常发出。
 *
 * <p>更关键的是：若只把「登记在途」放入临界区而把「写出到子进程」留在锁外，派发释放锁后、写出前若取消发生， 取消通知（{@code
 * notifications/cancelled}）会在子进程标准输入上<strong>早于</strong>原请求到达， 服务端因找不到目标 request id
 * 而忽略取消，随后原请求到达被服务端正常执行，导致取消静默失效。
 *
 * <p>因此传输必须把「是否已被取消」的判定、在途请求登记以及原请求提交（写出）包裹在同一个原子临界区内（见 {@link #dispatchOrAbort}）：
 *
 * <ul>
 *   <li>取消先取得锁 → 判定为已取消，原请求完全不写出，且不发送多余的取消通知；
 *   <li>派发先取得锁 → 原请求先完整写出到子进程，随后释放锁，取消方才能发送通知；子进程必然按顺序先收到原请求、后收到取消通知。
 * </ul>
 *
 * <p>实现按<strong>发起线程</strong>定位调用级状态，只允许在发起该请求的线程上调用（SDK 调用线程即绑定线程）。
 */
interface McpDispatchGate {

  /** 允许抛出受检 {@link IOException} 的派发写出动作。 */
  @FunctionalInterface
  interface CheckedDispatchAction {
    void run() throws IOException;
  }

  /**
   * 原子地判定本次派发是否仍然有效，并在有效时完成完整派发（登记在途 + 写出原请求）。
   *
   * @param requestId 协议 request id
   * @param action 包含在途登记与底层写出的完整派发动作，仅在允许派发时执行
   * @return {@code true} 表示派发已成功写出；{@code false} 表示已被取消拦截，原请求完全未写出
   * @throws IOException 当底层写出发生 IO 异常时抛出
   */
  boolean dispatchOrAbort(long requestId, CheckedDispatchAction action) throws IOException;
}
