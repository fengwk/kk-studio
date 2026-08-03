package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Typed SET_AGENT payload. */
public record SetAgentCommandPayload(String agentName) implements ThreadCommandPayload {

  public SetAgentCommandPayload {
    agentName = CommandValueValidation.requireCanonicalName(agentName, "agentName");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_AGENT;
  }
}
