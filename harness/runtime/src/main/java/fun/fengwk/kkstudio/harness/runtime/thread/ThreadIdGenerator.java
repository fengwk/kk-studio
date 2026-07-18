package fun.fengwk.kkstudio.harness.runtime.thread;

/** Thread / ThreadInput / ThreadEvent 主键生成端口。 */
public interface ThreadIdGenerator {
  long newThreadId();

  long newThreadInputId();

  long newThreadEventId();

  long newSessionEntryId();
}
