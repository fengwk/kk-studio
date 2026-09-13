package fun.fengwk.kkstudio.harness.mcp;

/**
 * 按 MCP request id 中止单个在途请求的传输层能力。
 *
 * <p>中止是请求级事实：必须同时（1）结束本地等待，（2）按协议通知服务端停止执行，且绝不影响同一连接上的其它并发请求。 只有自身持有在途请求状态的传输实现才能提供该能力。
 */
interface McpRequestAborter {

  /**
   * 中止指定 request id 的在途请求。
   *
   * @param requestId 协议 request id
   * @param reason 取消原因（进入 {@code notifications/cancelled}）
   * @return 是否确实存在并中止了该在途请求
   */
  boolean abort(long requestId, String reason);
}
