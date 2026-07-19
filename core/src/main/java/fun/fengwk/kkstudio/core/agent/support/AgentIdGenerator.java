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
  public static final String COMFYUI_WORKFLOW_API = "comfyui_workflow_api";
  public static final String HARNESS_SESSION = "harness_session";
  public static final String HARNESS_SESSION_ENTRY = "harness_session_entry";
  public static final String HARNESS_THREAD = "harness_thread";
  public static final String HARNESS_THREAD_INPUT = "harness_thread_input";
  public static final String HARNESS_THREAD_STOP = "harness_thread_stop";
  public static final String HARNESS_THREAD_EVENT = "harness_thread_event";
  public static final String MODEL_USAGE_RECORD = "model_usage_record";
  public static final String TOOL_INVOCATION = "tool_invocation";
  public static final String TOOL_ARTIFACT = "tool_artifact";
  public static final String TOOL_ENVIRONMENT = "tool_environment";
  public static final String CANVAS_DOCUMENT = "canvas_document";
  public static final String CANVAS_NODE = "canvas_node";
  public static final String CANVAS_LINK = "canvas_link";
  public static final String CANVAS_COMMAND = "canvas_command";
  public static final String CHAT = "chat";
  public static final String CHAT_SESSION = "chat_session";

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

  public static long nextComfyuiWorkflowApiId() {
    return GlobalSnowflakeIdGenerator.next(COMFYUI_WORKFLOW_API);
  }

  public static long nextHarnessSessionId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION);
  }

  public static long nextHarnessSessionEntryId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_SESSION_ENTRY);
  }

  public static long nextHarnessThreadId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD);
  }

  public static long nextHarnessThreadInputId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_INPUT);
  }

  public static long nextHarnessThreadStopId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_STOP);
  }

  public static long nextHarnessThreadEventId() {
    return GlobalSnowflakeIdGenerator.next(HARNESS_THREAD_EVENT);
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

  public static long nextToolEnvironmentId() {
    return GlobalSnowflakeIdGenerator.next(TOOL_ENVIRONMENT);
  }

  public static long nextCanvasDocumentId() {
    return GlobalSnowflakeIdGenerator.next(CANVAS_DOCUMENT);
  }

  public static long nextCanvasNodeId() {
    return GlobalSnowflakeIdGenerator.next(CANVAS_NODE);
  }

  public static long nextCanvasLinkId() {
    return GlobalSnowflakeIdGenerator.next(CANVAS_LINK);
  }

  public static long nextCanvasCommandId() {
    return GlobalSnowflakeIdGenerator.next(CANVAS_COMMAND);
  }

  public static long nextChatId() {
    return GlobalSnowflakeIdGenerator.next(CHAT);
  }

  public static long nextChatSessionId() {
    return GlobalSnowflakeIdGenerator.next(CHAT_SESSION);
  }
}
