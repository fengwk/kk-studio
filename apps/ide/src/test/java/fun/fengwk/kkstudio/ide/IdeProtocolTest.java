package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenMode;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewState;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeDirectoryEntry;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeDirectoryListing;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewSummary;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.util.List;
import java.util.UUID;

/** 意图：Daemon 回执一律校验后才可用；每个拒绝码映射到固定的结构化原因，协议坏值不写入快照。 */
class IdeProtocolTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Test
  void rejectsNonCanonicalProtocolValues() {
    DomainException uuid =
        assertThrows(DomainException.class, () -> IdeProtocol.protocolUuid("not-a-uuid", "viewId"));
    assertEquals("IDE_PROTOCOL_ERROR", uuid.reason());
    assertEquals("daemon did not return a canonical viewId", uuid.getMessage());

    assertEquals(
        "daemon did not return a positive binding epoch",
        assertThrows(DomainException.class, () -> IdeProtocol.requirePositiveEpoch(null))
            .getMessage());
    assertEquals(
        "daemon did not return a positive binding epoch",
        assertThrows(DomainException.class, () -> IdeProtocol.requirePositiveEpoch(0L))
            .getMessage());
    assertEquals(
        "daemon did not return an IDE view state",
        assertThrows(DomainException.class, () -> IdeProtocol.requireViewState(null)).getMessage());
  }

  @Test
  void mapsStatesAndModesToWireVocabulary() {
    assertEquals(IdeViewState.OPEN, IdeProtocol.protocolState(EnvironmentIdeViewState.OPEN));
    assertEquals(IdeViewState.CLOSING, IdeProtocol.protocolState(EnvironmentIdeViewState.CLOSING));
    assertEquals(IdeOpenMode.CREATE, IdeProtocol.protocolMode(EnvironmentIdeOpenMode.CREATE));
    assertEquals(IdeOpenMode.RESUME, IdeProtocol.protocolMode(EnvironmentIdeOpenMode.RESUME));
  }

  @Test
  void convertsSnapshotToDtoWithoutLosingEpochOrState() {
    IdeViewSnapshotStore.View view = view(IdeViewState.CLOSING);
    IdeViewSnapshotStore.Snapshot snapshot =
        new IdeViewSnapshotStore.Snapshot(IDENTITY, 9L, List.of(view));

    EnvironmentIdeViewsDTO dto = IdeProtocol.toViews(IDENTITY, snapshot);
    assertEquals(IDENTITY.daemonInstanceId(), dto.getDaemonInstanceId());
    assertEquals(9L, dto.getRevision());
    assertEquals(ENV.toString(), dto.getViews().get(0).getEnvironmentId());
    assertEquals(EnvironmentIdeViewState.CLOSING, dto.getViews().get(0).getState());
    assertEquals(7L, dto.getViews().get(0).getBindingEpoch());
  }

  @Test
  void invalidSnapshotSummaryKeepsTheOriginalCause() {
    IdeViewSummary invalid =
        IdeViewSummary.newBuilder()
            .setViewId("not-a-uuid")
            .setBrowserSessionId(UUID.randomUUID().toString())
            .setEventNodeId(UUID.randomUUID().toString())
            .setConnectionId(UUID.randomUUID().toString())
            .setClaimId(UUID.randomUUID().toString())
            .setBindingEpoch(1L)
            .setDirectory("/dir")
            .setWorkspacePath("/ws")
            .setState(IdeViewState.OPEN)
            .build();

    DomainException error =
        assertThrows(DomainException.class, () -> IdeProtocol.toSnapshotView(invalid));
    assertEquals("IDE_PROTOCOL_ERROR", error.reason());
  }

  @Test
  void directoryListingRequiresTheReplyPayload() {
    DomainException missing =
        assertThrows(
            DomainException.class,
            () -> IdeProtocol.toDirectoryListing(IdeResult.newBuilder().setOk(true).build()));
    assertEquals("daemon did not return a directory listing", missing.getMessage());

    IdeResult reply =
        IdeResult.newBuilder()
            .setOk(true)
            .setDirectoryListing(
                IdeDirectoryListing.newBuilder()
                    .setPath("/root")
                    .setTruncated(true)
                    .addChildren(IdeDirectoryEntry.newBuilder().setName("a").setPath("/root/a"))
                    .build())
            .build();
    EnvironmentIdeDirectoryListingDTO listing = IdeProtocol.toDirectoryListing(reply);
    assertEquals("/root", listing.getPath());
    assertNull(listing.getParentPath());
    assertTrue(listing.isTruncated());
    assertEquals("a", listing.getChildren().get(0).getName());
    assertEquals("/root/a", listing.getChildren().get(0).getPath());
  }

  @Test
  void rejectionMapsEachDaemonCodeToItsStableReason() {
    assertEquals("VIEW_GONE", IdeProtocol.rejection(code("VIEW_GONE")).reason());
    assertEquals(
        "VIEW_OWNED_BY_OTHER_SESSION",
        IdeProtocol.rejection(code("VIEW_OWNED_BY_OTHER_SESSION")).reason());
    assertEquals("STALE_BINDING", IdeProtocol.rejection(code("STALE_BINDING")).reason());
    assertEquals("CLAIM_CONFLICT", IdeProtocol.rejection(code("CLAIM_CONFLICT")).reason());
    assertEquals(
        "IDE_CAPACITY_EXCEEDED", IdeProtocol.rejection(code("IDE_CAPACITY_EXCEEDED")).reason());
    assertEquals("IDE_CLEANUP_FAILED", IdeProtocol.rejection(code("CLEANUP_FAILED")).reason());
    assertEquals("IDE_CLOSE_CANCELLED", IdeProtocol.rejection(code("CANCELLED")).reason());
    assertEquals("DIRTY", IdeProtocol.rejection(code("DIRTY")).reason());
    assertEquals("IDE_UNAVAILABLE", IdeProtocol.rejection(code("BUSY")).reason());
    assertEquals("environment", IdeProtocol.rejection(code("SOMETHING_ELSE")).resource());
    assertEquals(
        "IDE command was rejected", IdeProtocol.rejection(code("SOMETHING_ELSE")).getMessage());
  }

  @Test
  void failureFactoriesKeepTheirStableCoordinates() {
    assertEquals("IDE_OUTCOME_UNKNOWN", IdeProtocol.outcomeUnknown("x", null).reason());
    assertEquals("IDE_PROTOCOL_ERROR", IdeProtocol.protocolFailure("x").reason());
    assertTrue(IdeProtocol.isViewGone(code("VIEW_GONE")));
    assertFalse(IdeProtocol.isViewGone(IdeResult.newBuilder().setOk(true).build()));
    assertEquals(
        ENV.toString(), IdeProtocol.toDto(IDENTITY, view(IdeViewState.OPEN)).getEnvironmentId());
  }

  private static IdeResult code(String code) {
    return IdeResult.newBuilder().setOk(false).setCode(code).build();
  }

  private static IdeViewSnapshotStore.View view(IdeViewState state) {
    return new IdeViewSnapshotStore.View(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa"),
        UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"),
        UUID.fromString("12345678-1234-1234-1234-123456789abc"),
        UUID.fromString("87654321-4321-4321-4321-cba987654321"),
        7L,
        "/dir",
        "/ws",
        state);
  }
}
