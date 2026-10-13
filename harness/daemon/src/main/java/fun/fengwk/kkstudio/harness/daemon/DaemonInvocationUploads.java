package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;

/**
 * 调用终态编码所需的资源上传端口：由运行时以资源传输客户端与最近一次 WELCOME 通告的字节预算实现。
 *
 * <p>调用协调器只提供一次调用的等待边界（是否仍可继续、剩余预算），不感知预签名票据、控制帧或重连；因此资源传输语义只有一处归属。
 */
interface DaemonInvocationUploads {

  /** 本次连接 WELCOME 通告的单条/聚合资源字节预算；{@code 0} 表示尚未通告。 */
  long maxResourceBytes();

  /**
   * 绑定一次调用的上传端口。
   *
   * <p>预算在创建端口时读取一次：上传期间连接失效只暂停等待并等待同实例恢复，不因预算被复位而把合法上传误判为失败。
   */
  DaemonResourceUploader uploaderFor(Deadline deadline);

  /** 一次上传的等待边界，由被调用的 invocation 提供。 */
  interface Deadline {

    /** 调用是否仍可继续等待（未取消、未终结且未超出 deadline）。 */
    boolean canContinue();

    /** 距离 deadline 的剩余毫秒数；已超出或不可继续时为 0。 */
    long remainingMillis();
  }
}
