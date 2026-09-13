package fun.fengwk.kkstudio.platform.cloudfs.event;

/** Cloud File System 变更失效信号源。供 Web 事件通道订阅并触发权威快照回读。 */
public interface CloudFilesEventSource {

  /** 订阅 CFS 变更失效信号。返回的句柄在 close 时注销订阅。 */
  AutoCloseable subscribe(Runnable consumer);
}
