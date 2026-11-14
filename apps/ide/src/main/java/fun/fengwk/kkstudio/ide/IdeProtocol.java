package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewState;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.List;
import java.util.UUID;

/**
 * IDE 控制面的协议边界：Daemon 回执的取值校验、状态/模式映射、快照与 DTO 转换，以及 IDE 结构化失败工厂。
 *
 * <p>回执里的字符串都不可信：任何非规范值都翻译为 {@code IDE_PROTOCOL_ERROR}，绝不把坏回执写进权威快照。
 */
final class IdeProtocol {

  private IdeProtocol() {}

  /** Daemon 回执里的 canonical UUID；非规范形态是协议错误。 */
  static UUID protocolUuid(String value, String field) {
    try {
      return IdeUuids.requireInput(value, field);
    } catch (DomainException error) {
      throw protocolFailure("daemon did not return a canonical " + field);
    }
  }

  /** Daemon 回执里的 bindingEpoch：必填且为正数。 */
  static long requirePositiveEpoch(Long value) {
    if (value == null || value <= 0) {
      throw protocolFailure("daemon did not return a positive binding epoch");
    }
    return value;
  }

  static EnvironmentIdeViewState requireViewState(EnvironmentIdeViewState state) {
    if (state == null) {
      throw protocolFailure("daemon did not return an IDE view state");
    }
    return state;
  }

  static IdeViewState protocolState(EnvironmentIdeViewState state) {
    return switch (state) {
      case OPEN -> IdeViewState.OPEN;
      case CLOSING -> IdeViewState.CLOSING;
    };
  }

  static IdeOpenMode protocolMode(EnvironmentIdeOpenMode mode) {
    return switch (mode) {
      case CREATE -> IdeOpenMode.CREATE;
      case RESUME -> IdeOpenMode.RESUME;
    };
  }

  static List<IdeViewSnapshotStore.View> toSnapshotViews(IdeResult reply) {
    return reply.getViewsList().stream().map(IdeProtocol::toSnapshotView).toList();
  }

  static IdeViewSnapshotStore.View toSnapshotView(IdeViewSummary view) {
    try {
      return new IdeViewSnapshotStore.View(
          protocolUuid(view.getViewId(), "viewId"),
          protocolUuid(view.getBrowserSessionId(), "browserSessionId"),
          protocolUuid(view.getEventNodeId(), "eventNodeId"),
          protocolUuid(view.getConnectionId(), "connectionId"),
          protocolUuid(view.getClaimId(), "claimId"),
          view.getBindingEpoch(),
          IdeInputs.requireProtocolPath(view.getDirectory(), "directory"),
          IdeInputs.requireProtocolPath(view.getWorkspacePath(), "workspacePath"),
          view.getState());
    } catch (DomainException error) {
      throw error;
    } catch (RuntimeException error) {
      throw protocolFailure("daemon returned an invalid IDE view snapshot", error);
    }
  }

  static EnvironmentIdeViewDTO toDto(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.View view) {
    EnvironmentIdeViewDTO dto = new EnvironmentIdeViewDTO();
    dto.setViewId(view.viewId().toString());
    dto.setEnvironmentId(identity.environmentId().toString());
    dto.setBrowserSessionId(view.browserSessionId().toString());
    dto.setEventNodeId(view.eventNodeId().toString());
    dto.setConnectionId(view.connectionId().toString());
    dto.setClaimId(view.claimId().toString());
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setBindingEpoch(view.bindingEpoch());
    dto.setDirectory(view.directory());
    dto.setWorkspacePath(view.workspacePath());
    dto.setState(EnvironmentIdeViewState.valueOf(view.state().name()));
    return dto;
  }

  static EnvironmentIdeViewsDTO toViews(
      DaemonConnectionIdentity identity, IdeViewSnapshotStore.Snapshot snapshot) {
    EnvironmentIdeViewsDTO dto = new EnvironmentIdeViewsDTO();
    dto.setDaemonInstanceId(identity.daemonInstanceId());
    dto.setRevision(snapshot.revision());
    dto.setViews(snapshot.views().stream().map(view -> toDto(identity, view)).toList());
    return dto;
  }

  static EnvironmentIdeDirectoryListingDTO toDirectoryListing(IdeResult reply) {
    if (!reply.hasDirectoryListing()) {
      throw protocolFailure("daemon did not return a directory listing");
    }
    var listing = reply.getDirectoryListing();
    EnvironmentIdeDirectoryListingDTO dto = new EnvironmentIdeDirectoryListingDTO();
    dto.setPath(listing.getPath());
    dto.setParentPath(listing.hasParentPath() ? listing.getParentPath() : null);
    dto.setTruncated(listing.getTruncated());
    dto.setChildren(
        listing.getChildrenList().stream()
            .map(
                child -> {
                  EnvironmentIdeDirectoryListingDTO.Entry entry =
                      new EnvironmentIdeDirectoryListingDTO.Entry();
                  entry.setName(child.getName());
                  entry.setPath(child.getPath());
                  return entry;
                })
            .toList());
    return dto;
  }

  /** Daemon 拒绝回执到结构化领域错误的唯一映射。 */
  static DomainException rejection(IdeResult reply) {
    String message =
        !reply.hasMessage() || reply.getMessage().isBlank()
            ? "IDE command was rejected"
            : reply.getMessage();
    String code = reply.hasCode() ? reply.getCode() : "";
    return switch (code) {
      case "VIEW_GONE" -> viewGone(message);
      case "VIEW_OWNED_BY_OTHER_SESSION" -> viewOwnedByOther();
      case "STALE_BINDING" -> staleBinding(message);
      case "CLAIM_CONFLICT" -> DomainException.conflict("CLAIM_CONFLICT", message);
      case "IDE_CAPACITY_EXCEEDED" -> DomainException.conflict("IDE_CAPACITY_EXCEEDED", message);
      case "CLEANUP_FAILED" -> DomainException.unavailable("IDE_CLEANUP_FAILED", message);
      case "CANCELLED" -> DomainException.validationReason("IDE_CLOSE_CANCELLED", message);
      case "DIRTY" -> DomainException.validationReason("DIRTY", message);
      case "UNAVAILABLE", "BUSY", "VIEW_IN_USE" -> DomainException.unavailable(
          "IDE_UNAVAILABLE", message);
      default -> DomainException.validation("environment", message);
    };
  }

  static boolean isViewGone(IdeResult reply) {
    return reply.hasCode() && "VIEW_GONE".equals(reply.getCode());
  }

  static DomainException viewGone(String message) {
    return DomainException.unavailable("VIEW_GONE", message);
  }

  static DomainException viewOwnedByOther() {
    return DomainException.conflict(
        "VIEW_OWNED_BY_OTHER_SESSION", "IDE view is owned by another browser session");
  }

  static DomainException staleBinding(String message) {
    return DomainException.conflict("STALE_BINDING", message);
  }

  static DomainException outcomeUnknown(String message, Throwable cause) {
    return DomainException.unavailable("IDE_OUTCOME_UNKNOWN", message, cause);
  }

  static DomainException protocolFailure(String message) {
    return DomainException.unavailable("IDE_PROTOCOL_ERROR", message);
  }

  static DomainException protocolFailure(String message, Throwable cause) {
    return DomainException.unavailable("IDE_PROTOCOL_ERROR", message, cause);
  }
}
