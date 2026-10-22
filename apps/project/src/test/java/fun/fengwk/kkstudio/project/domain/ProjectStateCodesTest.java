package fun.fengwk.kkstudio.project.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** {@link ProjectStateCode} 字符集与 {@link ProjectWorkflowReservedState} 保留编码契约。 */
class ProjectStateCodesTest {

  /** 状态编码是项目内自然字符串：大写字母开头，只含大写字母、数字与下划线，最长 64 字符。 */
  @Test
  void acceptsNaturalUppercaseCodes() {
    assertEquals("INIT", ProjectStateCode.of("INIT").value());
    assertEquals("INIT", ProjectStateCode.of("INIT").toString());
    assertEquals("A", ProjectStateCode.of("A").toString());
    assertEquals("A_1", ProjectStateCode.of("A_1").toString());
    assertEquals(64, ProjectStateCode.of("A".repeat(64)).value().length());
  }

  /** 大小写折叠、数字开头、分隔符、首尾空白与超长编码都不合法，不做任何归一化或别名。 */
  @Test
  void rejectsCodesOutsideCharset() {
    List<String> invalid =
        List.of("", "init", "1INIT", "INIT-1", "INIT ", " INIT", "INIT.ME", "A".repeat(65));
    for (String value : invalid) {
      assertThrows(IllegalArgumentException.class, () -> ProjectStateCode.of(value));
    }
    assertThrows(NullPointerException.class, () -> ProjectStateCode.of(null));
  }

  /** 保留编码固定为 INIT/BLOCKED/DONE，其他编码都是工作阶段。 */
  @Test
  void reservesInitBlockedDoneOnly() {
    assertEquals(ProjectStateCode.of("INIT"), ProjectWorkflowReservedState.INIT.code());
    assertEquals(ProjectStateCode.of("BLOCKED"), ProjectWorkflowReservedState.BLOCKED.code());
    assertEquals(ProjectStateCode.of("DONE"), ProjectWorkflowReservedState.DONE.code());
    assertTrue(ProjectWorkflowReservedState.isReserved(ProjectStateCode.of("DONE")));
    assertFalse(ProjectWorkflowReservedState.isReserved(ProjectStateCode.of("DESIGN")));
    assertThrows(NullPointerException.class, () -> ProjectWorkflowReservedState.isReserved(null));
  }
}
