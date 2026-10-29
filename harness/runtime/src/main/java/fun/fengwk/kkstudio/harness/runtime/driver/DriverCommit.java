package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;

import java.time.Instant;
import java.util.UUID;

/**
 * Runtime 提供给 Driver 的事务内历史提交端口。
 *
 * <p>只暴露 Entry 的语义追加原语；不暴露 Thread update、Work 请求或 claim 围栏，这些仍由 Runtime 在 Driver 返回后统一执行。
 * 端口不开启事务、不执行外部 I/O。
 */
public interface DriverCommit {

  UUID nextId();

  Instant now();

  void insertEntry(Entry entry);
}
