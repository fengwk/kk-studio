package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.DaemonPresignedGet;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.PresignedGet;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceDownloadRequest;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceDownloadTicket;
import fun.fengwk.kkstudio.platform.daemon.protocol.TicketState;

import java.util.UUID;

/** Issues tickets only for archive transfers frozen into the matching live daemon invocation. */
final class DaemonDownloadCoordinator {

  private static final String FAILURE_MESSAGE = "resource download is unavailable";

  record PreparedDownload(
      DaemonConnectionSession session,
      ActiveInvocation active,
      String invocationId,
      UUID transferId,
      UUID blobId,
      boolean failed) {}

  PreparedDownload prepare(
      DaemonConnectionSession session,
      ActiveInvocation active,
      String invocationId,
      ResourceDownloadRequest request) {
    UUID transferId = UUID.fromString(request.getTransferId());
    if (active == null) {
      return new PreparedDownload(session, null, invocationId, transferId, null, true);
    }
    synchronized (active) {
      UUID blobId = active.downloadTransfers.get(transferId);
      if (active.isTerminal()
          || active.deliveredGeneration != session.generation()
          || blobId == null) {
        return new PreparedDownload(session, active, invocationId, transferId, null, true);
      }
      return new PreparedDownload(session, active, invocationId, transferId, blobId, false);
    }
  }

  void executeDownload(
      PreparedDownload prepared,
      DaemonResourceTicketService ticketService,
      DaemonInvocationTracker tracker) {
    DaemonResourceTicketService.DownloadTicket ticket;
    if (prepared.failed()
        || prepared.active() == null
        || !tracker.isActive(prepared.active())
        || prepared.active().deliveredGeneration != prepared.session().generation()) {
      ticket = new DaemonResourceTicketService.DownloadTicket.Failed(FAILURE_MESSAGE);
    } else {
      try {
        ticket =
            ticketService.download(
                prepared.session().environmentId(),
                prepared.invocationId(),
                prepared.transferId(),
                prepared.blobId());
      } catch (RuntimeException failure) {
        ticket = new DaemonResourceTicketService.DownloadTicket.Failed(FAILURE_MESSAGE);
      }
      if (!tracker.isActive(prepared.active())
          || prepared.active().deliveredGeneration != prepared.session().generation()) {
        ticket = new DaemonResourceTicketService.DownloadTicket.Failed(FAILURE_MESSAGE);
      }
    }
    offer(prepared, ticket);
  }

  private static void offer(
      PreparedDownload prepared, DaemonResourceTicketService.DownloadTicket ticket) {
    ResourceDownloadTicket.Builder response =
        ResourceDownloadTicket.newBuilder().setTransferId(prepared.transferId().toString());
    if (ticket instanceof DaemonResourceTicketService.DownloadTicket.Ready ready) {
      DaemonPresignedGet get = ready.presignedGet();
      response
          .setState(TicketState.TICKET_READY)
          .setPresignedGet(
              PresignedGet.newBuilder()
                  .setMethod(get.method())
                  .setUrl(get.url())
                  .setExpiresAtEpochMs(get.expiresAtEpochMs())
                  .build())
          .setSize(ready.size())
          .setSha256(ready.sha256());
    } else {
      DaemonResourceTicketService.DownloadTicket.Failed failure =
          (DaemonResourceTicketService.DownloadTicket.Failed) ticket;
      response.setState(TicketState.TICKET_FAILED).setMessage(failure.message());
    }
    Envelope envelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.RESOURCE_DOWNLOAD_TICKET)
            .setEnvironmentId(prepared.session().environmentId().value().toString())
            .setInvocationId(prepared.invocationId())
            .setResourceDownloadTicket(response.build())
            .build();
    prepared.session().offer(envelope);
  }
}
