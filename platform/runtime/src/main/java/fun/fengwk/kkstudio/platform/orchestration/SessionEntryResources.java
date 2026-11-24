package fun.fengwk.kkstudio.platform.orchestration;

import fun.fengwk.kkstudio.common.resource.ResourceUri;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Shared scan of blob resources referenced by persisted Session Entry messages. */
final class SessionEntryResources {

  private SessionEntryResources() {}

  /** Retains each distinct resource referenced by copied history for the target session. */
  static void retain(SessionBlobRefManager refManager, UUID sessionId, Collection<Entry> entries) {
    for (UUID id : blobIds(entries)) {
      refManager.retainRef(sessionId, id);
    }
  }

  /** Scans structured resources and links registered by tool-result materialization. */
  static List<UUID> blobIds(Collection<Entry> entries) {
    Set<UUID> resources = new TreeSet<>();
    for (Entry entry : entries) {
      EntryPayload payload = entry.payload();
      List<AgentMessageContent> contents =
          switch (payload) {
            case MessagePayload message -> message.message().contents();
            case CustomMessagePayload message -> message.message().contents();
            case NotificationPayload message -> message.message().contents();
            case AssistantAbortedPayload message -> message.message().contents();
            default -> List.of();
          };
      collect(contents, resources, false);
    }
    return List.copyOf(resources);
  }

  private static void collect(
      List<AgentMessageContent> contents, Set<UUID> resources, boolean toolResult) {
    for (AgentMessageContent content : contents) {
      if (content instanceof ResourceMessageContent resource) {
        resources.add(resource.blobId());
      } else if (content instanceof ToolResultMessageContent result) {
        collect(result.contents(), resources, true);
      } else if (toolResult && content instanceof TextMessageContent text) {
        resources.addAll(ResourceUri.referencedFiles(text.text()));
      } else if (toolResult && content instanceof JsonMessageContent json) {
        resources.addAll(ResourceUri.referencedFiles(json.json()));
      }
    }
  }
}
