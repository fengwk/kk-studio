package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;

import java.util.List;
import java.util.function.Consumer;

/**
 * AgentFactory 负责从 session tree 装配 Agent 运行态视图。
 *
 * <p>实际工作：在内部构建 {@link AgentSessionWriter} / {@link AgentAssistantRunner} /
 * {@link AgentToolOrchestrator} 三个协作者，使用注入的 {@link AgentRuntimeConfigResolver}，
 * 然后构造 {@link Agent}。
 *
 * @author fengwk
 */
public class AgentFactory {

  /** 构造一个 Agent 所需的共享依赖。 */
  public record Dependencies(
      ToolRegistry toolRegistry,
      ToolCallExecutor toolCallExecutor,
      SessionManager sessionManager,
      SessionEventMessageProjector sessionEventMessageProjector,
      AgentEventHandler agentEventHandler,
      AgentRuntimeConfigResolver runtimeConfigResolver) {

    public Dependencies {
      requireNonNull(toolRegistry, "toolRegistry");
      requireNonNull(toolCallExecutor, "toolCallExecutor");
      requireNonNull(sessionManager, "sessionManager");
      requireNonNull(sessionEventMessageProjector, "sessionEventMessageProjector");
      requireNonNull(agentEventHandler, "agentEventHandler");
      requireNonNull(runtimeConfigResolver, "runtimeConfigResolver");
    }

    private static <T> T requireNonNull(T value, String name) {
      if (value == null) {
        throw new IllegalArgumentException(name + " must not be null");
      }
      return value;
    }
  }

  public Agent load(
      String sessionId,
      String agentName,
      String provider,
      String model,
      String variant,
      UserRequestQueue userRequestQueue,
      AgentScheduler agentScheduler,
      ModelRetryConfig modelRetryConfig,
      Dependencies deps) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
    if (deps == null) {
      throw new IllegalArgumentException("deps must not be null");
    }
    if (userRequestQueue == null) {
      throw new IllegalArgumentException("userRequestQueue must not be null");
    }
    if (agentScheduler == null) {
      throw new IllegalArgumentException("agentScheduler must not be null");
    }
    if (modelRetryConfig == null) {
      throw new IllegalArgumentException("modelRetryConfig must not be null");
    }

    Session session = deps.sessionManager().getSession(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    Branch branch = session.currentBranch();
    List<SessionEvent> branchEvents = deps.sessionManager().loadBranchEvents(branch);

    AgentSessionWriter writer = new AgentSessionWriter(
        session,
        branch,
        branchEvents,
        deps.sessionManager(),
        deps.sessionEventMessageProjector(),
        deps.agentEventHandler());

    // 构造 Agent 协作者时需要先有 Agent 自身（用于 enqueueSignal / startToolBatch / continueAssistant 回调）。
    // 用 holder 解决循环引用。
    AgentToAssistants holder = new AgentToAssistants();

    AgentToolOrchestrator tools = new AgentToolOrchestrator(
        writer,
        deps.toolRegistry(),
        deps.toolCallExecutor(),
        holder.enqueueSignal,
        holder.continueAssistant);

    AgentAssistantRunner assistant = new AgentAssistantRunner(
        writer,
        holder.enqueueSignal,
        holder.startToolBatch);

    Agent agent = new Agent(
        writer, assistant, tools, deps.runtimeConfigResolver(),
        userRequestQueue, agentScheduler, modelRetryConfig,
        agentName, provider, model, variant);

    holder.bindAgent(agent);
    return agent;
  }

  /**
   * 解决 Agent → assistant → Agent 的循环引用：先以 lambda 形式持有 Agent 引用，
   * Agent 构造完后再注入。
   */
  private static final class AgentToAssistants {

    private Agent agent;

    final Consumer<AgentSignal> enqueueSignal =
        signal -> agent.enqueueSignal(signal);

    final Consumer<List<ToolCall>> startToolBatch =
        toolCalls -> agent.startToolBatch(toolCalls);

    final Runnable continueAssistant =
        () -> agent.continueAssistantFromToolBatch();

    void bindAgent(Agent agent) {
      this.agent = agent;
    }
  }
}
