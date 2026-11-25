package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.idText;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactDTO;

class HarnessRuntimeRequestMapperTargetTest {

  @Test
  void mapperRejectsNonCanonicalIdentityAndNegativeVersion() {
    // 真实 DTO 映射拒绝大写 UUID 与负版本游标。
    HarnessThreadCompactDTO uppercase = new HarnessThreadCompactDTO();
    uppercase.setExpectedVersion(1L);
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toCompactThreadCommand(
                "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", uppercase));

    HarnessThreadCompactDTO negative = new HarnessThreadCompactDTO();
    negative.setExpectedVersion(-1L);
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toCompactThreadCommand(idText(1), negative));
  }
}
