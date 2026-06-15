package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;

import java.util.List;

/**
 * AgentFactory 负责从 session tree 中装配 Agent 运行态视图。
 *
 * @author fengwk
 */
public class AgentFactory {

    public Agent load(String sessionId,
                      String agentName,
                      String provider,
                      String model,
                      String variant,
                      UserRequestQueue userRequestQueue,
                      AgentEventHandler agentEventHandler,
                      ToolRegistry toolRegistry,
                      SessionManager sessionManager,
                      SessionEventMessageProjector sessionEventMessageProjector,
                      AgentRegistry agentRegistry,
                      ModelRegistry modelRegistry,
                      ProviderRegistry providerRegistry,
                      ProviderManager providerManager,
                      AgentScheduler agentScheduler,
                      ModelRetryConfig modelRetryConfig) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        if (userRequestQueue == null) {
            throw new IllegalArgumentException("userRequestQueue must not be null");
        }
        if (agentEventHandler == null) {
            throw new IllegalArgumentException("agentEventHandler must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        if (sessionManager == null) {
            throw new IllegalArgumentException("sessionManager must not be null");
        }
        if (sessionEventMessageProjector == null) {
            throw new IllegalArgumentException("sessionEventMessageProjector must not be null");
        }
        if (agentRegistry == null) {
            throw new IllegalArgumentException("agentRegistry must not be null");
        }
        if (modelRegistry == null) {
            throw new IllegalArgumentException("modelRegistry must not be null");
        }
        if (providerRegistry == null) {
            throw new IllegalArgumentException("providerRegistry must not be null");
        }
        if (providerManager == null) {
            throw new IllegalArgumentException("providerManager must not be null");
        }
        if (agentScheduler == null) {
            throw new IllegalArgumentException("agentScheduler must not be null");
        }
        if (modelRetryConfig == null) {
            throw new IllegalArgumentException("modelRetryConfig must not be null");
        }

        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        Branch branch = session.currentBranch();
        List<SessionEvent> branchEvents = sessionManager.loadBranchEvents(branch);
        SessionEventProjection projection = sessionEventMessageProjector.project(branchEvents);

        return new Agent(
            session,
            branch,
            branchEvents,
            projection.agentInfo(),
            projection.modelInfo(),
            agentName,
            provider,
            model,
            variant,
            userRequestQueue,
            agentEventHandler,
            toolRegistry,
            sessionManager,
            sessionEventMessageProjector,
            agentRegistry,
            modelRegistry,
            providerRegistry,
            providerManager,
            agentScheduler,
            modelRetryConfig);
    }

}
