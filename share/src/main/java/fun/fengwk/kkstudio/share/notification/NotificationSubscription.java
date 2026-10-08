package fun.fengwk.kkstudio.share.notification;

/** A failed recovery is terminal and visible; it never silently resumes an invalid projection. */
public interface NotificationSubscription extends AutoCloseable {
  enum State {
    ACTIVE,
    FAILED,
    CLOSED
  }

  State state();

  @Override
  void close();
}
