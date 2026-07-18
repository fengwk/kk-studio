package fun.fengwk.kkstudio.core.harness.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical resolver for harness AgentSnapshot. Extracted from DatabaseTaskRuntime so that the
 * create-root-session command path, the run transaction path and the durable task runtime all share
 * one implementation.
 */
@Component
public class HarnessAgentSnapshotResolver {

  private final HarnessSessionEntryMapper sessionEntryMapper;
  private final ObjectMapper objectMapper;
  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();

  public HarnessAgentSnapshotResolver(
      HarnessSessionEntryMapper sessionEntryMapper, ObjectMapper objectMapper) {
    this.sessionEntryMapper = sessionEntryMapper;
    this.objectMapper = objectMapper;
  }

  /** Resolve the latest AGENT_SNAPSHOT for the session (session-wide latest by id). */
  public AgentSnapshot snapshotOnCurrentPath(HarnessSessionDO session) {
    if (session == null) {
      throw new IllegalArgumentException("session must not be null");
    }
    HarnessSessionEntryDO entry =
        sessionEntryMapper.findLatestByType(
            session.getId(), SessionEntryType.AGENT_SNAPSHOT.value());
    if (entry == null) {
      throw new IllegalStateException("session has no frozen agent snapshot: " + session.getId());
    }
    SessionEntryPayload payload =
        entryCodec.decode(SessionEntryType.AGENT_SNAPSHOT, entry.getPayloadJson());
    return ((AgentSnapshotEntryPayload) payload).snapshot();
  }

  /** Resolve AGENT_SNAPSHOT on a specific head path. */
  public AgentSnapshot snapshotOnPath(long sessionId, long headEntryId) {
    HarnessSessionEntryDO entry =
        sessionEntryMapper.findLatestOnPathByType(
            sessionId, headEntryId, SessionEntryType.AGENT_SNAPSHOT.value());
    if (entry == null) {
      throw new IllegalStateException(
          "session has no frozen agent snapshot on path head=" + headEntryId);
    }
    SessionEntryPayload payload =
        entryCodec.decode(SessionEntryType.AGENT_SNAPSHOT, entry.getPayloadJson());
    return ((AgentSnapshotEntryPayload) payload).snapshot();
  }

  /** Build the AgentSnapshot for a brand-new session created from this AgentDefinition. */
  public AgentSnapshot snapshotForDefinition(AgentDefinitionDO definition) {
    if (definition == null) {
      throw new IllegalArgumentException("agent definition must not be null");
    }
    try {
      JsonNode config = objectMapper.readTree(definition.getConfigJson());
      List<String> tools = strings(config, "tools");
      List<String> skills = strings(config, "skills");
      List<String> allowed = strings(config, "allowedSubagents");
      JsonNode policy = config.path("executionPolicy");
      return new AgentSnapshot(
          definition.getSystemPrompt(),
          String.valueOf(definition.getModelId()),
          definition.getVariant(),
          tools,
          skills,
          allowed,
          objectMapper.writeValueAsString(
              policy.isMissingNode() ? objectMapper.createObjectNode() : policy));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("agent config is invalid", error);
    }
  }

  private static List<String> strings(JsonNode config, String name) {
    JsonNode values = config.path(name);
    if (values.isMissingNode() || values.isNull()) {
      return List.of();
    }
    if (!values.isArray()) {
      throw new IllegalArgumentException("agent config " + name + " must be an array");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual() || value.textValue().isBlank()) {
        throw new IllegalArgumentException(
            "agent config " + name + " must contain non-blank strings");
      }
      result.add(value.textValue());
    }
    return List.copyOf(result);
  }
}
