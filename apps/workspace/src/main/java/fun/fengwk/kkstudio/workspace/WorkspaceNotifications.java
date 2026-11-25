package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.notification.SubscriptionFanOut;

import java.util.UUID;
import java.util.function.Consumer;

/** Cross-node workspace invalidations. PostgreSQL only delivers notifications after commit. */
@Component
public class WorkspaceNotifications {
  public static final String CHANNEL = "workspace_space_changed";
  private final SubscriptionFanOut<Change> changes = new SubscriptionFanOut<>();

  public sealed interface Change permits Tree, Space, Threads, Resync {}

  public record Tree(long treeVersion) implements Change {}

  public record Space(long version) implements Change {}

  public record Threads() implements Change {}

  public record Resync() implements Change {}

  static void notify(JdbcClient jdbc, UUID spaceId, String kind, long version) {
    jdbc.sql("select pg_notify(:channel, :payload)")
        .param("channel", CHANNEL)
        .param("payload", spaceId + ":" + kind + ":" + version)
        .query()
        .listOfRows();
  }

  public Runnable subscribe(UUID spaceId, Consumer<Change> consumer) {
    return changes.subscribe(spaceId, consumer);
  }

  public void onNotification(String payload) {
    UUID id;
    Change change;
    try {
      String[] fields = payload.split(":", -1);
      if (fields.length != 3) {
        throw new IllegalArgumentException("invalid workspace notification");
      }
      id = Uuids.parseCanonical(fields[0], "spaceId");
      long version = DecimalLongs.parseNonNegative(fields[2], "version");
      change =
          switch (fields[1]) {
            case "tree" -> new Tree(version);
            case "space" -> new Space(version);
            default -> throw new IllegalArgumentException("invalid workspace notification");
          };
    } catch (IllegalArgumentException ex) {
      resync();
      return;
    }
    changes.publish(id, change);
  }

  public void threads(UUID space) {
    changes.publish(space, new Threads());
  }

  public void resync() {
    changes.broadcast(new Resync());
  }
}
