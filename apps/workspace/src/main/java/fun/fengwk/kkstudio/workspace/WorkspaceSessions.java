package fun.fengwk.kkstudio.workspace;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.Names;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.TextDTO;
import fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery;
import fun.fengwk.kkstudio.platform.harness.store.HarnessPlacementQuery.PublicThread;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.IdleThreadOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.SessionInitialSettings;
import fun.fengwk.kkstudio.workspace.dto.BranchCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.ForkCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.SessionCreateDTO;
import fun.fengwk.kkstudio.workspace.dto.SessionCreatedDTO;
import fun.fengwk.kkstudio.workspace.dto.ThreadRenameDTO;
import fun.fengwk.kkstudio.workspace.dto.TurnCutDTO;
import fun.fengwk.kkstudio.workspace.dto.WorkspaceThreadPlacementDTO;

import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceSessions {
  private final ChatSpaceService spaces;
  private final JdbcClient jdbc;
  private final WorkspacePlacements placements;
  private final HarnessPlacementQuery query;
  private final WorkspaceTreeService tree;
  private final ObjectProvider<HarnessRuntime> runtime;
  private final HarnessCommandAcceptanceOrchestrator acceptance;
  private final IdleThreadOrchestrator idle;
  private final SessionDeletionOrchestrator deletion;
  private final SessionInitialSettings settings;

  WorkspaceSessions(
      ChatSpaceService spaces,
      JdbcClient jdbc,
      WorkspacePlacements placements,
      HarnessPlacementQuery query,
      WorkspaceTreeService tree,
      ObjectProvider<HarnessRuntime> runtime,
      HarnessCommandAcceptanceOrchestrator acceptance,
      IdleThreadOrchestrator idle,
      SessionDeletionOrchestrator deletion,
      SessionInitialSettings settings) {
    this.spaces = spaces;
    this.jdbc = jdbc;
    this.placements = placements;
    this.query = query;
    this.tree = tree;
    this.runtime = runtime;
    this.acceptance = acceptance;
    this.idle = idle;
    this.deletion = deletion;
    this.settings = settings;
  }

  @Transactional
  public SessionCreatedDTO create(
      StudioPrincipal actor, UUID spaceId, UUID key, SessionCreateDTO request) {
    return create(actor, spaceId, key, request, null);
  }

  SessionCreatedDTO create(
      StudioPrincipal actor,
      UUID spaceId,
      UUID key,
      SessionCreateDTO request,
      String explicitAgent) {
    var space = spaces.lock(actor, spaceId);
    UUID session = ChatSpaceService.derivedId("session", spaceId, key);
    UUID thread = ChatSpaceService.derivedId("main", spaceId, key);
    String hash =
        WorkspacePlacements.fingerprint(
            explicitAgent == null ? request : List.of(request, explicitAgent));
    var replay = placements.replay(spaceId, session, hash);
    if (replay.isPresent()) {
      return new SessionCreatedDTO(
          session,
          thread,
          replay.get().initialCommandId(),
          replay.get().createdTreeVersion().toString());
    }
    UUID folderId =
        request.folderId() == null ? placements.firstRootFolder(spaceId) : request.folderId();
    placements.requireFolder(spaceId, folderId);
    if (request.message() == null || request.message().isEmpty()) {
      throw WorkspaceInput.invalid("Message is required");
    }
    var model = request.modelSelection();
    var branch =
        settings.resolve(
            explicitAgent == null ? space.defaultAgentName() : explicitAgent,
            request.environmentId() == null
                ? space.defaultEnvironmentId()
                : request.environmentId(),
            modelSelection(model));
    boolean yolo =
        request.yoloEnabled() == null ? space.defaultYoloEnabled() : request.yoloEnabled();
    var message =
        new AgentMessage(
            AgentMessageRole.USER,
            HarnessRuntimeRequestMapper.toUserMessageContents(request.message()));
    var accepted =
        acceptance.createSession(
            actor.userId(),
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewRootSession(session, thread, branch, yolo),
                List.of(new NewThreadCommand(new UserMessageCommandPayload(message), key))));
    if (accepted.replayed()) {
      throw DomainException.conflict("IDEMPOTENCY_KEY_REUSED", "Session identity is already used");
    }
    UUID command = accepted.acceptedCommands().getFirst().idempotencyKey();
    placements.insert(
        spaceId,
        folderId,
        session,
        Kind.SESSION,
        branch.agentName(),
        hash,
        space.treeVersion() + 1,
        command);
    return new SessionCreatedDTO(session, thread, command, Long.toString(spaces.advance(spaceId)));
  }

  void submitScheduledMessage(UUID userId, UUID thread, String text, UUID key) {
    var message =
        new AgentMessage(
            AgentMessageRole.USER,
            HarnessRuntimeRequestMapper.toUserMessageContents(List.of(new TextDTO(text))));
    acceptance.acceptOnThread(
        userId,
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(thread),
            List.of(new NewThreadCommand(new UserMessageCommandPayload(message), key))));
  }

  @Transactional
  public BranchCreatedDTO branch(
      StudioPrincipal actor, UUID space, UUID session, UUID key, TurnCutDTO request) {
    spaces.lock(actor, space);
    placements.require(space, session, Kind.SESSION);
    UUID thread = ChatSpaceService.derivedId("branch", session, key);
    if (query.thread(thread).isEmpty()) {
      requireSource(session, request.sourceThreadId());
    }
    try {
      runtime
          .getObject()
          .createIdleBranch(
              WorkspaceInput.required(request.sourceThreadId(), "sourceThreadId"),
              WorkspaceInput.required(request.turnEndEntryId(), "turnEndEntryId"),
              thread,
              harnessTitle(request.title()));
    } catch (DomainException ex) {
      throw creationConflict(ex);
    }
    return new BranchCreatedDTO(session, thread);
  }

  @Transactional
  public ForkCreatedDTO fork(
      StudioPrincipal actor, UUID spaceId, UUID session, UUID key, TurnCutDTO request) {
    var space = spaces.lock(actor, spaceId);
    UUID fork = ChatSpaceService.derivedId("fork-session", session, key);
    UUID thread = ChatSpaceService.derivedId("fork-main", session, key);
    String hash = WorkspacePlacements.fingerprint(request);
    var replay = placements.replay(spaceId, fork, hash);
    if (replay.isPresent()) {
      return new ForkCreatedDTO(fork, thread, replay.get().createdTreeVersion().toString());
    }
    var edge = placements.require(spaceId, session, Kind.SESSION);
    requireSource(session, request.sourceThreadId());
    try {
      idle.createIdleFork(
          request.sourceThreadId(),
          WorkspaceInput.required(request.turnEndEntryId(), "turnEndEntryId"),
          fork,
          thread,
          harnessTitle(request.title()));
    } catch (DomainException ex) {
      throw creationConflict(ex);
    }
    placements.insert(
        spaceId,
        edge.folderId(),
        fork,
        Kind.SESSION,
        edge.agentName(),
        hash,
        space.treeVersion() + 1,
        null);
    return new ForkCreatedDTO(fork, thread, Long.toString(spaces.advance(spaceId)));
  }

  // The response includes execution facts in the same transaction as the rename.
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public WorkspaceThreadPlacementDTO rename(
      StudioPrincipal actor, UUID space, UUID id, ThreadRenameDTO request) {
    var current = spaces.lock(actor, space);
    requireThread(space, id);
    try {
      runtime
          .getObject()
          .renameThread(
              new RenameThreadCommand(id, harnessTitle(request.title())),
              WorkspaceInput.version(request.expectedVersion()));
    } catch (DomainException ex) {
      throw creationConflict(ex);
    }
    spaces.treeChanged(space, current.treeVersion());
    return tree.threadPlacement(space, id);
  }

  @Transactional
  public long deleteThread(
      StudioPrincipal actor, UUID spaceId, UUID id, long treeVersion, long version) {
    var space = spaces.lock(actor, spaceId);
    requireThread(spaceId, id);
    ChatSpaceService.requireTreeVersion(space, treeVersion);
    ScheduleReferences.requireThreadUnused(jdbc, id);
    deletion.deleteThread(id, version);
    return spaces.advance(spaceId);
  }

  @Transactional
  public long deleteSession(StudioPrincipal actor, UUID spaceId, UUID id, long treeVersion) {
    var space = spaces.lock(actor, spaceId);
    var edge = placements.require(spaceId, id, Kind.SESSION);
    ChatSpaceService.requireTreeVersion(space, treeVersion);
    ScheduleReferences.requireSessionUnused(jdbc, id);
    deletion.deleteSession(id, ids -> placements.delete(spaceId, edge));
    return spaces.advance(spaceId);
  }

  private void requireSource(UUID session, UUID id) {
    query
        .thread(WorkspaceInput.required(id, "sourceThreadId"))
        .filter(thread -> thread.sessionId().equals(session))
        .orElseThrow(ChatSpaceService::resourceNotFound);
  }

  private PublicThread requireThread(UUID space, UUID id) {
    var thread = query.thread(id).orElseThrow(ChatSpaceService::resourceNotFound);
    placements.require(space, thread.sessionId(), Kind.SESSION);
    return thread;
  }

  private static ModelSelection modelSelection(HarnessModelSelectionDTO request) {
    if (request == null) {
      return null;
    }
    try {
      return new ModelSelection(
          WorkspaceInput.required(request.getModelName(), "modelName"), request.getVariant());
    } catch (IllegalArgumentException ex) {
      throw WorkspaceInput.invalid(ex.getMessage());
    }
  }

  private static String harnessTitle(String title) {
    try {
      return Names.normalize(WorkspaceInput.required(title, "title"));
    } catch (IllegalArgumentException ex) {
      throw WorkspaceInput.invalid(ex.getMessage());
    }
  }

  private static DomainException creationConflict(DomainException ex) {
    if ("THREAD_NAME_CONFLICT".equals(ex.reason())) {
      return DomainException.conflict(
          "THREAD_NAME_EXISTS", "A thread in this session already uses this title");
    }
    if ("INVALID_TURN_END".equals(ex.reason())) {
      return DomainException.validationReason(
          "INVALID_TURN_END", "Entry is not a turn end on the source path");
    }
    if ("THREAD_ID_REUSED".equals(ex.reason())) {
      return DomainException.conflict(
          "IDEMPOTENCY_KEY_REUSED", "Creation key already used for a different request");
    }
    return ex;
  }
}
