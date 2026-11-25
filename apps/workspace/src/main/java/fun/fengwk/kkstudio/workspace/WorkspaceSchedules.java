package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.account.AccountScheduleSuspension;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.account.StudioUserLock;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDraftDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePatchDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePreviewDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePreviewRequestDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunDTO.Result;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunPageDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleTriggerDTO;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceSchedules implements AccountScheduleSuspension {
  private final ScheduleRepository repository;
  private final StudioUserLock users;
  private final ChatSpaceService spaces;
  private final ScheduleTargets targets;
  private final JdbcClient jdbc;
  private final DurableWorkStore work;

  WorkspaceSchedules(
      ScheduleRepository repository,
      StudioUserLock users,
      ChatSpaceService spaces,
      ScheduleTargets targets,
      JdbcClient jdbc,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.users = users;
    this.spaces = spaces;
    this.targets = targets;
    this.jdbc = jdbc;
    this.work = new DurableWorkStore("SCHEDULE", jdbc, transactions);
  }

  @Transactional(readOnly = true)
  public List<ScheduleDTO> list(StudioPrincipal actor, UUID space) {
    spaces.get(actor, space);
    return repository.list(space);
  }

  @Transactional(readOnly = true)
  public SchedulePreviewDTO preview(
      StudioPrincipal actor, UUID space, SchedulePreviewRequestDTO request) {
    spaces.get(actor, space);
    return new SchedulePreviewDTO(next(request.trigger(), request.timeZone(), repository.now(), 3));
  }

  @Transactional
  public ScheduleDTO create(StudioPrincipal actor, UUID space, UUID key, ScheduleDraftDTO draft) {
    lock(actor, space);
    UUID id = ChatSpaceService.derivedId("schedule", space, key);
    String hash = WorkspacePlacements.fingerprint(draft);
    var existing = repository.creationHash(space, id);
    if (existing.isPresent()) {
      if (!existing.get().equals(hash)) {
        throw DomainException.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Schedule creation key already used");
      }
      return repository.get(space, id, true);
    }
    var schedule = configure(actor, space, id, 1, draft, null);
    repository.insert(space, schedule, hash);
    refreshWork(schedule);
    WorkspaceNotifications.schedules(jdbc, space, id);
    return schedule;
  }

  @Transactional
  public ScheduleDTO patch(StudioPrincipal actor, UUID space, UUID id, SchedulePatchDTO patch) {
    lock(actor, space);
    var old = repository.get(space, id, true);
    requireVersion(old, WorkspaceInput.version(patch.expectedVersion()));
    var draft =
        new ScheduleDraftDTO(
            value(patch.title(), old.title()), value(patch.message(), old.message()),
            value(patch.enabled(), old.enabled()), value(patch.trigger(), old.trigger()),
            value(patch.timeZone(), old.timeZone()), value(patch.target(), old.target()));
    var schedule = configure(actor, space, id, old.version() + 1, draft, old);
    repository.update(space, schedule);
    if (!Objects.equals(schedule.nextDueAt(), old.nextDueAt())) {
      refreshWork(schedule);
    }
    WorkspaceNotifications.schedules(jdbc, space, id);
    return schedule;
  }

  @Transactional
  public void delete(StudioPrincipal actor, UUID space, UUID id, long expectedVersion) {
    lock(actor, space);
    requireVersion(repository.get(space, id, true), expectedVersion);
    work.delete(id);
    repository.delete(space, id);
    WorkspaceNotifications.schedules(jdbc, space, id);
  }

  @Transactional(readOnly = true)
  public ScheduleRunPageDTO runs(StudioPrincipal actor, UUID space, UUID id, String before) {
    spaces.get(actor, space);
    repository.get(space, id, false);
    return repository.runs(space, id, before);
  }

  @Transactional
  public ScheduleRunDTO run(StudioPrincipal actor, UUID space, UUID id, UUID key) {
    Instant requested = repository.now();
    lock(actor, space);
    return trigger(
        actor, space, repository.get(space, id, true), "manual:" + key, requested, false);
  }

  public Optional<WorkClaim> claim() {
    return work.claimNext(UUID.randomUUID(), Duration.ofMinutes(2));
  }

  public void release(WorkClaim claim) {
    work.release(claim);
  }

  @Transactional
  public void auto(WorkClaim claim) {
    UUID id = claim.targetId();
    var space = repository.spaceOf(id);
    if (space.isEmpty()) {
      return;
    }
    var owner = repository.ownerOf(space.get());
    if (!users.acquireEnabled(owner)) {
      return;
    }
    var actor = new StudioPrincipal(owner, "", StudioRole.USER);
    spaces.lock(actor, space.get());
    if (repository.creationHash(space.get(), id).isEmpty()) {
      return;
    }
    var schedule = repository.get(space.get(), id, true);
    Instant now = repository.now();
    Instant due = schedule.nextDueAt();
    if (!schedule.enabled()
        || due == null
        || due.isAfter(now)
        || work.find(id)
            .filter(
                w ->
                    w.wakeVersion() == claim.claimedWakeVersion()
                        && claim.leaseToken().equals(w.leaseToken())
                        && w.leaseUntil().isAfter(now)
                        && due.equals(w.availableAt()))
            .isEmpty()) {
      return;
    }
    var latest =
        ScheduleTiming.previous(schedule.trigger(), schedule.timeZone(), now)
            .filter(at -> !at.isBefore(due));
    Instant occurrence = latest.orElse(due);
    trigger(actor, space.get(), schedule, "auto:" + occurrence, occurrence, latest.isEmpty());
    Instant following =
        next(schedule.trigger(), schedule.timeZone(), now, 1).stream().findFirst().orElse(null);
    repository.advance(id, following);
    if (following == null) {
      work.complete(claim);
    } else {
      work.rescheduleAt(claim, following);
    }
  }

  private ScheduleRunDTO trigger(
      StudioPrincipal actor,
      UUID space,
      ScheduleDTO schedule,
      String occurrence,
      Instant scheduled,
      boolean expired) {
    var replay = repository.findRun(space, schedule.id(), occurrence);
    if (replay.isPresent()) {
      return replay.get();
    }
    UUID key =
        UUID.nameUUIDFromBytes((schedule.id() + ":" + occurrence).getBytes(StandardCharsets.UTF_8));
    Instant started = expired ? null : repository.now();
    UUID output =
        expired
            ? null
            : targets.submit(
                actor, space, schedule, key, repository.lastOutput(schedule.id()).orElse(null));
    String reason = expired ? "EXPIRED" : output == null ? "EXECUTION_NOT_SETTLED" : null;
    var record =
        new ScheduleRunDTO(
            UUID.randomUUID(),
            scheduled,
            started,
            schedule.timeZone(),
            output != null ? Result.SUBMITTED : Result.SKIPPED,
            reason,
            null);
    repository.insertRun(schedule.id(), occurrence, record, output);
    WorkspaceNotifications.schedules(jdbc, space, schedule.id());
    return repository.findRun(space, schedule.id(), occurrence).orElseThrow();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void suspend(UUID userId) {
    for (UUID space : repository.lockSpaces(userId)) {
      for (UUID id : repository.lockEnabled(space)) {
        repository.pause(id);
        work.delete(id);
        WorkspaceNotifications.schedules(jdbc, space, id);
      }
    }
  }

  private void lock(StudioPrincipal actor, UUID space) {
    if (!users.acquireEnabled(actor.userId())) {
      throw DomainException.unauthorized("NOT_AUTHENTICATED", "Account is disabled");
    }
    spaces.lock(actor, space);
  }

  private ScheduleDTO configure(
      StudioPrincipal actor,
      UUID space,
      UUID id,
      long version,
      ScheduleDraftDTO draft,
      ScheduleDTO old) {
    if (draft.title() == null
        || draft.title().isBlank()
        || draft.title().strip().length() > 256
        || draft.title().chars().anyMatch(Character::isISOControl)
        || draft.message() == null
        || draft.message().isBlank()
        || draft.enabled() == null) {
      throw invalid();
    }
    targets.validate(actor, space, draft.target());
    boolean timingUnchanged =
        old != null
            && old.trigger().equals(draft.trigger())
            && old.timeZone().equals(draft.timeZone());
    Instant due;
    if (timingUnchanged && (!draft.enabled() || old.enabled())) {
      due = draft.enabled() ? old.nextDueAt() : null;
    } else {
      var next = next(draft.trigger(), draft.timeZone(), repository.now(), 1);
      if (next.isEmpty()) {
        throw invalid();
      }
      due = draft.enabled() ? next.getFirst() : null;
    }
    return new ScheduleDTO(
        id,
        draft.title().strip(),
        draft.message(),
        draft.enabled(),
        draft.trigger(),
        draft.timeZone(),
        draft.target(),
        due,
        version);
  }

  private void refreshWork(ScheduleDTO schedule) {
    work.delete(schedule.id());
    if (schedule.nextDueAt() != null) {
      work.requestAt(schedule.id(), schedule.nextDueAt());
    }
  }

  private static void requireVersion(ScheduleDTO schedule, long expected) {
    if (schedule.version() != expected) {
      throw DomainException.versionConflict("schedule", expected, schedule.version());
    }
  }

  private static <T> T value(T value, T previous) {
    return value == null ? previous : value;
  }

  private static List<Instant> next(
      ScheduleTriggerDTO trigger, String zone, Instant now, int limit) {
    try {
      return ScheduleTiming.next(trigger, zone, now, limit);
    } catch (DateTimeException | IllegalArgumentException ex) {
      throw invalid();
    }
  }

  static DomainException invalid() {
    return DomainException.validationReason("INVALID_SCHEDULE", "Invalid schedule configuration");
  }
}
