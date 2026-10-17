package fun.fengwk.kkstudio.harness.daemon.coding;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Restart Manager and post-failure deletion diagnostics for owned test handshake files. */
public final class WindowsFileLockProbe {

  private static final int ERROR_MORE_DATA = 234;
  private static final int PROCESS_INFO_SIZE = 668;

  private WindowsFileLockProbe() {}

  static String owners(Path file) {
    return lockOwners(file) + deletionState(file);
  }

  private static String deletionState(Path file) {
    String attributes;
    try {
      DosFileAttributes dos = Files.readAttributes(file, DosFileAttributes.class);
      attributes =
          "[readonly="
              + dos.isReadOnly()
              + ",hidden="
              + dos.isHidden()
              + ",system="
              + dos.isSystem()
              + "]";
    } catch (IOException failure) {
      attributes = "[attributes=" + failure.getClass().getSimpleName() + "]";
    }
    try {
      return attributes + "[post-failure-delete=" + Files.deleteIfExists(file) + "]";
    } catch (IOException failure) {
      String reason =
          failure instanceof FileSystemException filesystem ? filesystem.getReason() : "";
      return attributes
          + "[post-failure-delete="
          + failure.getClass().getSimpleName()
          + ":"
          + reason
          + "]";
    }
  }

  private static String lockOwners(Path file) {
    IntByReference session = new IntByReference();
    int started = RestartManager.INSTANCE.RmStartSession(session, 0, new char[33]);
    if (started != 0) {
      return "[rm-start=" + started + "]";
    }
    try {
      int registered =
          RestartManager.INSTANCE.RmRegisterResources(
              session.getValue(),
              1,
              new WString[] {new WString(file.toString())},
              0,
              null,
              0,
              null);
      if (registered != 0) {
        return "[rm-register=" + registered + "]";
      }
      IntByReference needed = new IntByReference();
      IntByReference count = new IntByReference();
      IntByReference reasons = new IntByReference();
      int queried =
          RestartManager.INSTANCE.RmGetList(session.getValue(), needed, count, null, reasons);
      if (queried != ERROR_MORE_DATA) {
        return "[rm-list=" + queried + ",owners=0]";
      }
      count.setValue(needed.getValue());
      try (Memory processes = new Memory((long) needed.getValue() * PROCESS_INFO_SIZE)) {
        int listed =
            RestartManager.INSTANCE.RmGetList(
                session.getValue(), needed, count, processes, reasons);
        if (listed != 0) {
          return "[rm-list=" + listed + "]";
        }
        List<Long> owners = new ArrayList<>();
        for (int index = 0; index < count.getValue(); index++) {
          owners.add(Integer.toUnsignedLong(processes.getInt((long) index * PROCESS_INFO_SIZE)));
        }
        return "[owners=" + owners + ",parent=" + ProcessHandle.current().pid() + "]";
      }
    } finally {
      RestartManager.INSTANCE.RmEndSession(session.getValue());
    }
  }

  public interface RestartManager extends StdCallLibrary {

    RestartManager INSTANCE = Native.load("Rstrtmgr", RestartManager.class);

    int RmStartSession(IntByReference session, int flags, char[] key);

    int RmRegisterResources(
        int session,
        int files,
        WString[] names,
        int applications,
        Pointer processes,
        int services,
        Pointer serviceNames);

    int RmGetList(
        int session,
        IntByReference needed,
        IntByReference count,
        Pointer processes,
        IntByReference reasons);

    int RmEndSession(int session);
  }
}
