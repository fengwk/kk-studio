package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PosixProcessSession#parentsBeforeChildren(List)} 的确定性回归：整会话按成员逐个发信号之前，必须先把内核快照排成
 * 「父进程先于子进程」。
 *
 * <p>内核给出的 PID 枚举（macOS 的 {@code proc_listpids} 尤其）不保证父进程先出现；先给子进程发信号可能让父进程来不及执行自己的清理。
 * 因此这里是纯逻辑用例：只喂构造出的成员，不依赖任何平台命令、进程或内核查询，三个平台都必须真跑。顺序必须由父子拓扑决定，绝不按 PID 数值——PID 会回绕和复用。PID
 * 重复或父指针成环这类无法消歧的快照必须整体拒绝（返回 {@code null}），调用方据此绝不发信号。
 */
class PosixProcessSignalOrderTest {

  /** 输入被打乱（子进程先出现）时也必须重排成父先子后。 */
  @Test
  void ordersScrambledInputParentsBeforeChildren() {
    List<PosixProcessSession.GroupMember> scrambled =
        List.of(member(8, 5), member(3, 0), member(11, 8), member(5, 3));
    List<PosixProcessSession.GroupMember> ordered =
        PosixProcessSession.parentsBeforeChildren(scrambled);
    assertNotNull(ordered);
    assertEquals(List.of(3L, 5L, 8L, 11L), pids(ordered), "顺序必须由父子拓扑决定");
    assertParentsBeforeChildren(ordered);
  }

  /** 嵌套森林：每棵树内部父先子后，互不相连的根节点保持输入中的相遇顺序。 */
  @Test
  void ordersNestedForestParentsBeforeChildren() {
    List<PosixProcessSession.GroupMember> forest =
        List.of(
            member(4, 2), member(11, 10), member(2, 1), member(10, 0), member(1, 0), member(3, 1));
    List<PosixProcessSession.GroupMember> ordered =
        PosixProcessSession.parentsBeforeChildren(forest);
    assertNotNull(ordered);
    // 根 10 在输入中先于根 1，因此整棵 10 子树先被访问。
    assertEquals(List.of(10L, 1L, 11L, 2L, 3L, 4L), pids(ordered), "独立根节点必须保持输入相遇顺序");
    assertParentsBeforeChildren(ordered);
  }

  /** PID 回绕/复用：子进程的数值可以大于或小于父进程，顺序只能服从父子关系。 */
  @Test
  void ordersWrappingNumericPidsParentsBeforeChildren() {
    List<PosixProcessSession.GroupMember> wrapping =
        List.of(member(7, 0xFFFF_FFFFL), member(400, 900), member(0xFFFF_FFFFL, 0), member(900, 0));
    List<PosixProcessSession.GroupMember> ordered =
        PosixProcessSession.parentsBeforeChildren(wrapping);
    assertNotNull(ordered);
    assertEquals(List.of(0xFFFF_FFFFL, 900L, 7L, 400L), pids(ordered), "顺序绝不能按 PID 数值排：它会回绕和复用");
    assertParentsBeforeChildren(ordered);
  }

  /** 空输入是确定的空顺序，而不是「不可判定」。 */
  @Test
  void emptyInputYieldsEmptyOrder() {
    List<PosixProcessSession.GroupMember> ordered =
        PosixProcessSession.parentsBeforeChildren(List.of());
    assertNotNull(ordered, "空快照是确定的空顺序，绝不能当成不可判定");
    assertEquals(List.of(), ordered);
  }

  /** 同一个 pid 出现两次时整份快照无法消歧，必须整体拒绝。 */
  @Test
  void duplicatePidsAreRejected() {
    assertNull(
        PosixProcessSession.parentsBeforeChildren(List.of(member(5, 0), member(5, 0))),
        "重复的 pid 不能排出一个可信顺序");
    assertNull(
        PosixProcessSession.parentsBeforeChildren(
            List.of(member(9, 0), member(9, 3), member(3, 0))),
        "重复的 pid 即使父指针不同也不能消歧");
  }

  /** 父指针成环（含自环、以及夹在合法根旁边的环）时整份快照不可发信号。 */
  @Test
  void parentCyclesAreRejected() {
    assertNull(
        PosixProcessSession.parentsBeforeChildren(List.of(member(2, 3), member(3, 2))),
        "互相指向的父指针是环");
    assertNull(PosixProcessSession.parentsBeforeChildren(List.of(member(7, 7))), "自环也是环");
    assertNull(
        PosixProcessSession.parentsBeforeChildren(
            List.of(member(1, 0), member(2, 3), member(3, 2))),
        "只要存在从任何根都到达不了的成员，整份快照就不可用");
  }

  private static PosixProcessSession.GroupMember member(long pid, long parentPid) {
    // 启动身份与排序无关，这里用固定值，避免把排序断言和身份核验混在一起。
    return new PosixProcessSession.GroupMember(pid, Instant.EPOCH, parentPid);
  }

  private static List<Long> pids(List<PosixProcessSession.GroupMember> members) {
    return members.stream().map(PosixProcessSession.GroupMember::pid).toList();
  }

  /** 每个成员的父进程（若也在结果里）必须出现在它之前。 */
  private static void assertParentsBeforeChildren(List<PosixProcessSession.GroupMember> ordered) {
    Map<Long, Integer> indexByPid = new HashMap<>();
    for (int index = 0; index < ordered.size(); index++) {
      indexByPid.put(ordered.get(index).pid(), index);
    }
    for (PosixProcessSession.GroupMember member : ordered) {
      Integer parentIndex = indexByPid.get(member.parentPid());
      if (parentIndex != null) {
        assertTrue(
            parentIndex < indexByPid.get(member.pid()),
            "父进程 " + member.parentPid() + " 必须先于子进程 " + member.pid() + " 发信号");
      }
    }
  }
}
