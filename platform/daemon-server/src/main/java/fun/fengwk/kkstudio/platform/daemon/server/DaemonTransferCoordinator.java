package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.PresignedPut;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceUploadCommit;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceUploadRequest;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceUploadTicket;
import fun.fengwk.kkstudio.platform.daemon.protocol.TicketState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 资源上传控制面协调器：处理调用范围内的资源传输申请、提交、票据推进与释放。 */
final class DaemonTransferCoordinator {

  static final int MAX_TRANSFERS_PER_INVOCATION = 16;
  static final String TRANSFER_FAILURE_MESSAGE = "resource upload is unavailable";

  record PreparedTransfer(
      DaemonConnectionSession session,
      ActiveInvocation active,
      UUID transferId,
      DaemonResourceTicketService.TransferRequest request,
      UUID commitUploadId) {}

  PreparedTransfer prepareUploadReserve(
      DaemonConnectionSession session,
      ActiveInvocation active,
      ResourceUploadRequest request,
      long maxResourceBytes) {
    UUID transferId = UUID.fromString(request.getTransferId());
    if (request.getSize() > maxResourceBytes) {
      throw new ControlProtocolException(
          "resource transfer size exceeds the environment limit: " + transferId);
    }
    DaemonResourceTicketService.TransferRequest serviceRequest =
        new DaemonResourceTicketService.TransferRequest(
            transferId,
            request.getMediaType(),
            request.getName(),
            request.getSize(),
            request.getSha256());
    synchronized (active) {
      TransferBinding existing = active.transfers.get(transferId);
      if (existing != null) {
        if (!existing.request.equals(serviceRequest)) {
          throw new ControlProtocolException(
              "resource transfer is already bound to a different request: " + transferId);
        }
        return new PreparedTransfer(session, active, transferId, existing.request, null);
      }
      if (active.transfers.size() >= MAX_TRANSFERS_PER_INVOCATION) {
        throw new ControlProtocolException(
            "invocation "
                + active.invocationId
                + " exceeds "
                + MAX_TRANSFERS_PER_INVOCATION
                + " concurrent resource transfers");
      }
      active.transfers.put(transferId, new TransferBinding(serviceRequest));
    }
    return new PreparedTransfer(session, active, transferId, serviceRequest, null);
  }

  PreparedTransfer prepareUploadCommit(
      DaemonConnectionSession session, ActiveInvocation active, ResourceUploadCommit commit) {
    UUID transferId = UUID.fromString(commit.getTransferId());
    UUID commitUploadId = UUID.fromString(commit.getUploadId());
    synchronized (active) {
      TransferBinding existing = active.transfers.get(transferId);
      if (existing == null) {
        throw new ControlProtocolException(
            "resource upload commit references an unrequested transfer: " + transferId);
      }
      synchronized (existing) {
        if (existing.released) {
          throw new ControlProtocolException(
              "resource upload commit references a released transfer: " + transferId);
        }
        UUID owned = existing.currentUploadId;
        if (owned == null || !owned.equals(commitUploadId)) {
          throw new ControlProtocolException(
              "resource upload commit uploadId does not match the issued ticket: " + transferId);
        }
      }
    }
    return new PreparedTransfer(session, active, transferId, null, commitUploadId);
  }

  void executeTransfer(
      PreparedTransfer prepared,
      DaemonResourceTicketService ticketService,
      DaemonInvocationTracker tracker) {
    ActiveInvocation active = prepared.active();
    if (!tracker.isActive(active)) {
      return;
    }
    UUID transferId = prepared.transferId();
    TransferBinding binding;
    synchronized (active) {
      binding = active.transfers.get(transferId);
      if (binding == null) {
        return;
      }
    }
    boolean commit = prepared.commitUploadId() != null;
    DaemonResourceTicketService.Ticket ticket;
    UUID producedUploadId = null;
    boolean replay;
    synchronized (binding) {
      if (binding.released) {
        return;
      }
      ticket = commit ? binding.commitTicket() : binding.reserveTicket();
      replay = ticket != null;
      if (!replay) {
        try {
          ticket =
              commit
                  ? ticketService.commit(
                      active.environmentId,
                      active.invocationId.toString(),
                      prepared.commitUploadId())
                  : ticketService.reserve(
                      active.environmentId, active.invocationId.toString(), binding.request);
        } catch (RuntimeException error) {
          ticket = new DaemonResourceTicketService.Ticket.Failed(TRANSFER_FAILURE_MESSAGE);
        }
        producedUploadId = ticket.uploadId();
        if (producedUploadId != null) {
          binding.currentUploadId = producedUploadId;
        }
        if (ticket instanceof DaemonResourceTicketService.Ticket.Failed) {
          binding.recordFailure(commit);
        } else if (commit) {
          binding.commitTicket = ticket;
        } else {
          binding.reserveTicket = ticket;
        }
      }
    }
    if (producedUploadId != null && !tracker.isActive(active)) {
      releaseBinding(active, binding, ticketService);
      return;
    }
    ResourceUploadTicket wire = toWireTicket(transferId, ticket);
    if (tracker.isActive(active)) {
      Envelope ticketEnvelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.RESOURCE_UPLOAD_TICKET)
              .setEnvironmentId(active.environmentId.value().toString())
              .setInvocationId(active.invocationId.toString())
              .setResourceUploadTicket(wire)
              .build();
      prepared.session().offer(ticketEnvelope);
    }
  }

  private static ResourceUploadTicket toWireTicket(
      UUID transferId, DaemonResourceTicketService.Ticket ticket) {
    ResourceUploadTicket.Builder builder =
        ResourceUploadTicket.newBuilder().setTransferId(transferId.toString());
    switch (ticket) {
      case DaemonResourceTicketService.Ticket.Pending pending -> {
        builder.setState(TicketState.TICKET_PENDING);
        builder.setUploadId(pending.uploadId().toString());
        DaemonPresignedPut put = pending.presignedPut();
        PresignedPut.Builder putBuilder =
            PresignedPut.newBuilder().setMethod(put.method()).setUrl(put.url());
        if (put.headers() != null) {
          putBuilder.putAllHeaders(put.headers());
        }
        if (put.expiresAt() != null) {
          putBuilder.setExpiresAt(put.expiresAt().toString());
        }
        builder.setPresignedPut(putBuilder.build());
      }
      case DaemonResourceTicketService.Ticket.Ready ready -> {
        builder.setState(TicketState.TICKET_READY);
        builder.setUploadId(ready.uploadId().toString());
      }
      case DaemonResourceTicketService.Ticket.Failed failed -> {
        builder.setState(TicketState.TICKET_FAILED);
        builder.setMessage(failed.message());
      }
    }
    return builder.build();
  }

  Set<UUID> requireCompletedUploads(ActiveInvocation active, EnvironmentCapabilityResult result) {
    Set<UUID> referenced = new HashSet<>();
    List<UUID> declared = new ArrayList<>();
    for (ResultContent content : result.contents()) {
      if (content instanceof ResourceResultContent resource) {
        UUID uploadId = resource.resource().blobUploadId();
        if (uploadId == null) {
          throw new ControlProtocolException(
              "resource content must reference a global upload in a terminal result");
        }
        if (!referenced.add(uploadId)) {
          throw new ControlProtocolException(
              "terminal result declares the same upload twice: " + uploadId);
        }
        declared.add(uploadId);
      }
    }
    if (declared.isEmpty()) {
      return referenced;
    }
    List<TransferBinding> bindings;
    synchronized (active) {
      bindings = List.copyOf(active.transfers.values());
    }
    Map<UUID, TransferBinding> byUploadId = new HashMap<>();
    for (TransferBinding binding : bindings) {
      synchronized (binding) {
        if (binding.currentUploadId != null && !binding.released) {
          byUploadId.put(binding.currentUploadId, binding);
        }
      }
    }
    for (ResultContent content : result.contents()) {
      if (!(content instanceof ResourceResultContent resource)) {
        continue;
      }
      ResourceRef ref = resource.resource();
      UUID uploadId = ref.blobUploadId();
      TransferBinding binding = byUploadId.get(uploadId);
      if (binding == null) {
        throw new ControlProtocolException(
            "terminal result references an upload this invocation does not own: " + uploadId);
      }
      binding.requireReadyAndMatching(ref);
    }
    return referenced;
  }

  void releaseUnreferencedTransfers(
      ActiveInvocation active, Set<UUID> referenced, DaemonResourceTicketService ticketService) {
    List<TransferBinding> abandoned = new ArrayList<>();
    synchronized (active) {
      for (TransferBinding binding : active.transfers.values()) {
        if (binding.currentUploadId == null || !referenced.contains(binding.currentUploadId)) {
          abandoned.add(binding);
        }
      }
      abandoned.forEach(binding -> active.transfers.values().remove(binding));
    }
    for (TransferBinding binding : abandoned) {
      releaseBinding(active, binding, ticketService);
    }
  }

  void releaseAllTransfers(ActiveInvocation active, DaemonResourceTicketService ticketService) {
    List<TransferBinding> bindings;
    synchronized (active) {
      if (active.transfers.isEmpty()) {
        return;
      }
      bindings = List.copyOf(active.transfers.values());
      active.transfers.clear();
    }
    for (TransferBinding binding : bindings) {
      releaseBinding(active, binding, ticketService);
    }
  }

  private void releaseBinding(
      ActiveInvocation active, TransferBinding binding, DaemonResourceTicketService ticketService) {
    UUID uploadId;
    synchronized (binding) {
      if (binding.released) {
        return;
      }
      binding.released = true;
      uploadId = binding.currentUploadId;
    }
    if (uploadId == null) {
      return;
    }
    try {
      ticketService.release(active.environmentId, active.invocationId.toString(), uploadId);
    } catch (RuntimeException ignored) {
    }
  }
}
