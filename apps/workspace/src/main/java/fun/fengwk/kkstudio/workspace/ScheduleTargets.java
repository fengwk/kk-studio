package fun.fengwk.kkstudio.workspace;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.TextDTO;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTargetDTO;
import fun.fengwk.kkstudio.workspace.dto.SessionCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.TurnCutDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
class ScheduleTargets {
  private final WorkspacePlacements placements;
  private final HarnessPlacementQuery harness;
  private final ResourceAccess access;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final AgentRepository agents;
  private final WorkspaceSessions sessions;

  ScheduleTargets(
      WorkspacePlacements placements,
      HarnessPlacementQuery harness,
      ResourceAccess access,
      ObjectProvider<HarnessRuntime> runtimes,
      AgentRepository agents,
      WorkspaceSessions sessions) {
    this.placements = placements;
    this.harness = harness;
    this.access = access;
    this.runtimes = runtimes;
    this.agents = agents;
    this.sessions = sessions;
  }

  void validate(StudioPrincipal actor, UUID space, ScheduleTargetDTO target) {
    UUID source = requireTarget(actor, space, target);
    if (target.turnEndEntryId() != null) {
      try {
        runtimes.getObject().validateBranchCut(source, target.turnEndEntryId());
      } catch (DomainException ex) {
        if ("INVALID_TURN_END".equals(ex.reason())) {
          throw DomainException.validationReason("INVALID_TURN_END", "Invalid branch cut");
        }
        throw ex;
      }
    }
  }

  private UUID requireTarget(StudioPrincipal actor, UUID space, ScheduleTargetDTO t) {
    if (t == null || t.type() == null) {
      throw WorkspaceSchedules.invalid();
    }
    if (t.type() == ScheduleTargetDTO.Type.NEW_SESSION) {
      if (t.folderId() == null
          || t.agentName() == null
          || t.agentName().isBlank()
          || t.sessionId() != null
          || t.sourceThreadId() != null
          || t.turnEndEntryId() != null
          || t.threadId() != null) {
        throw WorkspaceSchedules.invalid();
      }
      placements.requireFolder(space, t.folderId());
      agents.getByName(t.agentName());
      return null;
    }
    boolean branch = t.type() == ScheduleTargetDTO.Type.BRANCH;
    if (t.folderId() != null
        || t.agentName() != null
        || t.sessionId() == null
        || (t.sourceThreadId() != null) != branch
        || (t.turnEndEntryId() != null) != branch
        || (t.threadId() != null) == branch) {
      throw WorkspaceSchedules.invalid();
    }
    access.require(actor, Kind.SESSION, t.sessionId());
    placements.require(space, t.sessionId(), Kind.SESSION);
    UUID id = branch ? t.sourceThreadId() : t.threadId();
    harness
        .thread(id)
        .filter(thread -> thread.sessionId().equals(t.sessionId()))
        .orElseThrow(ResourceAccess::notFound);
    return id;
  }

  /** Null means the relevant execution tree still has obligations or has been stopped. */
  UUID submit(StudioPrincipal actor, UUID space, ScheduleDTO schedule, UUID key, UUID previous) {
    var t = schedule.target();
    UUID source = requireTarget(actor, space, t);
    var runtime = runtimes.getObject();
    var roots = new ArrayList<UUID>();
    if (source != null) {
      roots.add(source);
    }
    switch (t.type()) {
      case NEW_SESSION -> roots.add(ChatSpaceService.derivedId("main", space, key));
      case BRANCH -> roots.add(ChatSpaceService.derivedId("branch", t.sessionId(), key));
      case CONTINUE -> previous = source;
    }
    if (previous != null) {
      roots.add(previous);
    }
    runtime.lockExecutionTrees(roots);
    if (previous != null
        && !runtime.executionSettled(previous, t.type() == ScheduleTargetDTO.Type.CONTINUE)) {
      return null;
    }
    var message = List.of(new TextDTO(schedule.message()));
    if (t.type() == ScheduleTargetDTO.Type.NEW_SESSION) {
      return sessions
          .create(
              actor,
              space,
              key,
              new SessionCreateDTO(t.folderId(), List.copyOf(message), null, null, null),
              t.agentName())
          .threadId();
    }
    UUID thread = source;
    if (t.type() == ScheduleTargetDTO.Type.BRANCH) {
      thread =
          sessions
              .branch(
                  actor,
                  space,
                  t.sessionId(),
                  key,
                  new TurnCutDTO(source, t.turnEndEntryId(), "Scheduled " + key))
              .threadId();
    }
    sessions.submitScheduledMessage(thread, schedule.message(), key);
    return thread;
  }
}
