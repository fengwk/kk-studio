package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

/** 一条已完成等待的 IDE 命令结果，以及命令进入传输时该连接背后的 Daemon 实例代际。 */
record IdeCall(IdeResult reply, DaemonConnectionIdentity identity) {}
