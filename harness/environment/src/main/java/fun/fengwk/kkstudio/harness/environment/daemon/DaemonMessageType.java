package fun.fengwk.kkstudio.harness.environment.daemon;

/** Daemon wire 协议的消息类型。 */
public enum DaemonMessageType {
  HELLO,
  READY,
  HEARTBEAT,
  /** Daemon 在关键状态变化时立即上报的完整 runtimeStatus；不带 invocationId。 */
  RUNTIME_STATUS,
  STARTED,
  PROGRESS,
  COMPLETED,
  FAILED,
  CANCELLED,
  /** 独立退出事实：payload 必须为空对象，不能合成结果终态。 */
  EXECUTION_EXITED,
  WELCOME,
  TEMPORARY_RESOURCE_POLICY,
  INVOKE,
  CANCEL,
  /** 同实例重连后取回在途调用：Daemon 对运行中的调用回放 STARTED 并继续执行，对已终结的调用重放终态与退出确认，绝不终止执行。 */
  RECOVER,
  RESOURCE_UPLOAD_REQUEST,
  RESOURCE_UPLOAD_TICKET,
  RESOURCE_UPLOAD_COMMIT,
  UPDATE,
  UPDATE_RESULT,
  IDE_COMMAND,
  IDE_RESULT,
  /** Server 下发的受控数据连接绑定；不带 invocationId。 */
  DATA_BIND,
  /** Server 下发的批量 IDE 使用证明；不带 invocationId。 */
  IDE_LEASE,
  ERROR
}
