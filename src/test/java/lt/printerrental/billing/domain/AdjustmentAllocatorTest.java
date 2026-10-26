package lt.printerrental.billing.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdjustmentAllocatorTest {
  final AdjustmentAllocator allocator = new AdjustmentAllocator();

  @Test
  void excessCorrectionCarriesForwardWithoutNegativeInvoice() {
    var id = UUID.randomUUID();
    var plan =
        allocator.apply(
            new BigDecimal("30.00"),
            List.of(new AdjustmentAllocator.Pending(id, new BigDecimal("-50.00"))));
    assertThat(plan.amountDue()).isZero();
    assertThat(plan.creditApplied()).isEqualByComparingTo("30.00");
    assertThat(plan.applied().get(0).remaining()).isEqualByComparingTo("-20.00");
  }

  @Test
  void debitAndCreditAreAppliedWithoutPretendingToBePayments() {
    var plan =
        allocator.apply(
            new BigDecimal("30.00"),
            List.of(
                new AdjustmentAllocator.Pending(UUID.randomUUID(), new BigDecimal("-50.00")),
                new AdjustmentAllocator.Pending(UUID.randomUUID(), new BigDecimal("10.00"))));
    assertThat(plan.amountDue()).isZero();
    assertThat(plan.debitApplied()).isEqualByComparingTo("10.00");
    assertThat(plan.creditApplied()).isEqualByComparingTo("40.00");
  }

  @Test
  void noCurrentAmountLeavesCreditAvailable() {
    var plan =
        allocator.apply(
            new BigDecimal("0.00"),
            List.of(new AdjustmentAllocator.Pending(UUID.randomUUID(), new BigDecimal("-50.00"))));
    assertThat(plan.applied()).isEmpty();
  }
}
