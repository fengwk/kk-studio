package fun.fengwk.kkstudio.agent.util;

import java.util.UUID;

import java.util.UUID;

/**
 * @author fengwk
 */
public class IdGenerator {

  private IdGenerator() {}

  public static String newSessionId() {
    return "se_" + rawUuid();
  }

  public static String newEventId() {
    return "ev_" + rawUuid();
  }

  private static String rawUuid() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
