package fun.fengwk.kkstudio.canvas.notification;

import fun.fengwk.kkstudio.share.notification.NotificationCodecs;
import fun.fengwk.kkstudio.share.notification.NotificationSignal;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;
import fun.fengwk.kkstudio.share.notification.VersionHint;

/** Statically declared notification topics for the Canvas domain. */
public final class CanvasNotifications {

  public static final NotificationTopic<VersionHint> REVISION =
      new NotificationTopic<>("canvas.revision", NotificationCodecs.VERSION_HINT, true);

  public static final NotificationTopic<NotificationSignal> FUNCTION_WORK =
      new NotificationTopic<>("canvas.function.work", NotificationCodecs.SIGNAL, true);

  private CanvasNotifications() {}
}
