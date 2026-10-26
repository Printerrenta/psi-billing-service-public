package lt.printerrental.settlement.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentAllocatorTest {
  final PaymentAllocator allocator = new PaymentAllocator();
  final LocalDate date = LocalDate.of(2026, 10, 1);

  PaymentAllocator.Debt debt(int day, String amount) {
    return new PaymentAllocator.Debt(
        UUID.randomUUID(), date.plusDays(day), date, date, new BigDecimal(amount));
  }

  PaymentAllocator.Payment payment(String amount) {
    return new PaymentAllocator.Payment(UUID.randomUUID(), date, new BigDecimal(amount));
  }

  @Test
  void oldestDebtIsCoveredFirstEvenWhenInputIsUnordered() {
    var old = debt(1, "20.00");
    var newer = debt(2, "30.00");
    var allocations = allocator.allocate(List.of(newer, old), List.of(payment("35.00")));
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).invoiceId()).isEqualTo(old.invoiceId());
    assertThat(allocations.get(0).amount()).isEqualByComparingTo("20.00");
    assertThat(allocations.get(1).amount()).isEqualByComparingTo("15.00");
  }

  @Test
  void overpaymentIsNotForcedIntoAnInvoice() {
    var result = allocator.allocate(List.of(debt(1, "20.00")), List.of(payment("35.00")));
    assertThat(
            result.stream()
                .map(PaymentAllocator.Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("20.00");
  }

  @Test
  void multiplePartialPaymentsNeverExceedDebt() {
    var result =
        allocator.allocate(List.of(debt(1, "10.00")), List.of(payment("7.00"), payment("7.00")));
    assertThat(
            result.stream()
                .map(PaymentAllocator.Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("10.00");
  }

  @Test
  void zeroPayableRequiresNoPayment() {
    assertThat(allocator.allocate(List.of(debt(1, "0.00")), List.of(payment("5.00")))).isEmpty();
  }

  @Test
  void negativeOrOverpreciseMoneyIsRejected() {
    assertThatThrownBy(() -> payment("0.00")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> debt(1, "-1.00")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payment("1.001")).isInstanceOf(IllegalArgumentException.class);
  }
}
