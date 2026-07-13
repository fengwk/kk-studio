package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.UUID;

/** 默认业务 id 生成器；数据库主键由 Store adapter 独立生成。 */
public final class UuidSessionIdGenerator implements SessionIdGenerator {
  @Override
  public String newSessionId() {
    return "se_" + rawUuid();
  }

  @Override
  public String newEntryId() {
    return "en_" + rawUuid();
  }

  private String rawUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
