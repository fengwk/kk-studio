package fun.fengwk.kkstudio.core.ai.catalog.support;

import java.util.UUID;

/**
 * @author fengwk
 */
public class AgentIdentifierGenerator {

  private AgentIdentifierGenerator() {}

  public static String newSessionId() {
    return "se_" + rawUuid();
  }

  public static String newEventId() {
    return "ev_" + rawUuid();
  }

  public static String newRunId() {
    return "rn_" + rawUuid();
  }

  private static String rawUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
