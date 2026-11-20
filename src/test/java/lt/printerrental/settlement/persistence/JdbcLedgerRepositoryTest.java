package lt.printerrental.settlement.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lt.printerrental.billing.persistence.JdbcReadingProjectionStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class JdbcLedgerRepositoryTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void usesConfiguredCustomSchemaInLedgerQueries() {
    var db = mock(JdbcTemplate.class);
    var invoiceId = UUID.randomUUID();
    when(db.queryForObject(anyString(), eq(Long.class), eq("alice"))).thenReturn(1L);
    when(db.queryForObject(anyString(), eq(Boolean.class), eq("alice"))).thenReturn(true);
    when(db.queryForObject(anyString(), eq(BigDecimal.class), eq(invoiceId)))
        .thenReturn(BigDecimal.ZERO);

    var repo = new JdbcLedgerRepository(db, mapper, "custom_ledger");
    repo.lockAccount("alice");
    repo.knownOwner("alice");
    repo.allocated(invoiceId);
    repo.allocatedByInvoice(List.of(invoiceId));
    repo.appliedAdjustmentsByInvoice(List.of(invoiceId));

    var updateSql = ArgumentCaptor.forClass(String.class);
    verify(db).update(updateSql.capture(), eq("alice"));
    assertThat(updateSql.getValue())
        .contains("custom_ledger.customer_accounts")
        .doesNotContain("billing.customer_accounts");

    var lockSql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForObject(lockSql.capture(), eq(Long.class), eq("alice"));
    assertThat(lockSql.getValue())
        .contains("custom_ledger.customer_accounts")
        .doesNotContain("billing.customer_accounts");

    var ownerSql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForObject(ownerSql.capture(), eq(Boolean.class), eq("alice"));
    assertThat(ownerSql.getValue())
        .contains("custom_ledger.contracts")
        .doesNotContain("billing.contracts");

    var allocatedSql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForObject(allocatedSql.capture(), eq(BigDecimal.class), eq(invoiceId));
    assertThat(allocatedSql.getValue())
        .contains("custom_ledger.payment_allocations")
        .doesNotContain("billing.payment_allocations");

    var batchSql = ArgumentCaptor.forClass(String.class);
    verify(db, times(2))
        .query(batchSql.capture(), any(PreparedStatementSetter.class), any(RowMapper.class));
    assertThat(batchSql.getAllValues())
        .hasSize(2)
        .allSatisfy(
            sql -> {
              assertThat(sql).contains("custom_ledger.");
              assertThat(sql).doesNotContain("billing.");
            });
  }

  @Test
  void emptyBatchLookupsReturnEmptyMapsWithoutQueryingDatabase() {
    var db = mock(JdbcTemplate.class);
    var repo = new JdbcLedgerRepository(db, mapper, "custom_ledger");

    assertThat(repo.allocatedByInvoice(List.of())).isEmpty();
    assertThat(repo.appliedAdjustmentsByInvoice(List.of())).isEmpty();
    verifyNoInteractions(db);
  }

  @Test
  void rejectsInvalidSchemaNamesInLedgerAndReadingRepositories() {
    var db = mock(JdbcTemplate.class);
    for (var bad : List.of("", "UPPER", "123_schema", "billing; DROP TABLE invoices")) {
      assertThatThrownBy(() -> new JdbcLedgerRepository(db, mapper, bad))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new JdbcReadingProjectionStore(db, mapper, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new JdbcLedgerRepository(db, mapper, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JdbcReadingProjectionStore(db, mapper, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readingProjectionStoreUsesConfiguredCustomSchema() {
    var db = mock(JdbcTemplate.class);
    var store = new JdbcReadingProjectionStore(db, mapper, "tenant_readings");
    var contractId = UUID.randomUUID();
    var readingId = UUID.randomUUID();
    var date = LocalDate.of(2026, 1, 31);

    store.receive(contractId, readingId, date, List.of(10L, 0L, 0L, 0L), 1L);
    store.readings(contractId);
    store.streamRevision(contractId, date);

    var insertSql = ArgumentCaptor.forClass(String.class);
    verify(db).update(insertSql.capture(), eq(contractId), eq(date), eq(readingId), anyString(), eq(1L));
    assertThat(insertSql.getValue())
        .contains("tenant_readings.reading_projections")
        .doesNotContain("billing.reading_projections");

    var readingsSql = ArgumentCaptor.forClass(String.class);
    verify(db).query(readingsSql.capture(), any(RowMapper.class), eq(contractId));
    assertThat(readingsSql.getValue())
        .contains("tenant_readings.reading_projections")
        .doesNotContain("billing.reading_projections");

    var revisionSql = ArgumentCaptor.forClass(String.class);
    verify(db).query(revisionSql.capture(), any(RowMapper.class), eq(contractId), eq(date));
    assertThat(revisionSql.getValue())
        .contains("tenant_readings.reading_projections")
        .doesNotContain("billing.reading_projections");
  }
}
