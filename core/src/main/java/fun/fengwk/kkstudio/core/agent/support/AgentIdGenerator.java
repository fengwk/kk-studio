package fun.fengwk.kkstudio.core.agent.support;

import fun.fengwk.convention4j.springboot.starter.snowflake.GlobalSnowflakeIdGenerator;

/**
 * 资源 id 生成器。
 *
 * @author fengwk
 */
public final class AgentIdGenerator {

  public static final String AGENT_PROVIDER = "agent_provider";
  public static final String AGENT_MODEL = "agent_model";
  public static final String AGENT_DEFINITION = "agent_definition";
  public static final String AGENT_SESSION = "agent_session";
  public static final String AGENT_SESSION_EVENT = "agent_session_event";
  public static final String AGENT_RUN = "agent_run";
  public static final String COMFYUI_WORKFLOW_API = "comfyui_workflow_api";
  public static final String HARNESS_SESSION = "harness_session";
  public static final String HARNESS_SESSION_ENTRY = "harness_session_entry";
  public static final String HARNESS_RUN = "harness_run";
  public static final String HARNESS_RUN_EVENT = "harness_run_event";
  public static final String HARNESS_RUN_CONTROL_MESSAGE = "harness_run_control_message";
  public static final String MODEL_USAGE_RECORD = "model_usage_record";
  public static final String TOOL_INVOCATION = "tool_invocation";
  public static final String TOOL_ARTIFACT = "tool_artifact";

  private AgentIdGenerator() {}

  public static long nextProviderId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_PROVIDER);
  }

  public static long nextModelId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_MODEL);
  }

  public static long nextAgentId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_DEFINITION);
  }

  public static long nextSessionId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_SESSION);
  }

  public static long nextEventId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_SESSION_EVENT);
  }

  public static long nextRunId() {
    return GlobalSnowflakeIdGenerator.next(AGENT_RUN);
  }

  public static long nextComfyuiWorkflowApiId() {
    return GlobalSnowflakeIdGenerator.next(COMFYUI_WORKFLOW_API);
  }

  public static long nextHarnessSessionId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION);
  }

  public static long nextHarnessSessionEntryId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION_ENTRY);
  }

  public static long nextHarnessRunId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_RUN);
  }

  public static long nextHarnessRunEventId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_RUN_EVENT);
  }

  public static long nextHarnessRunControlMessageId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_RUN_CONTROL_MESSAGE);
  }

  public static long nextModelUsageRecordId() {
    return GlobalSnowflakeIdGenerator.next(MODEL_USAGE_RECORD);
  }

  public static long nextToolInvocationId() {
    return GlobalSnowflakeIdGenerator.next(TOOL_INVOCATION);
  }

  public static long nextToolArtifactId() {
    return GlobalSnowflakeIdGenerator.next(TOOL_ARTIFACT);
  }
}
