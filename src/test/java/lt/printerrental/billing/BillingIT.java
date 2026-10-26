package lt.printerrental.billing;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import lt.printerrental.integration.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks",
      "RENTAL_SERVICE_URL=http://rental.test",
      "OUTBOX_INITIAL_DELAY_MS=3600000",
      "INBOX_INITIAL_DELAY_MS=3600000"
    })
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(BillingIT.Config.class)
class BillingIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry p) {
    p.add(
        "spring.datasource.url",
        () ->
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=billing");
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new lt.printerrental.testing.TestPostgresContainer("postgres:17-alpine");

  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean lt.printerrental.auth.UserIdentityResolver resolver;
  @MockitoBean ContractAccess remote;
  @MockitoBean lt.printerrental.events.EventPublisher publisher;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ContractProjectionUpdater projections;
  @Autowired ContractSnapshotStore streams;

  static class Time extends Clock {
    static Instant now = Instant.parse("2026-04-06T12:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Config {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    Clock testClock() {
      return new Time();
    }
  }

  void as(String subject, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(subject, "", "ROLE_" + role));
  }

  ContractSnapshot snapshot(UUID id) {
    return new ContractSnapshot(
        id,
        UUID.randomUUID(),
        "alice",
        "maker/large",
        true,
        true,
        "ACTIVE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 3, 31),
        null,
        LocalDate.of(2026, 1, 1),
        List.of(0L, 0L, 0L, 0L),
        1,
        List.of(
            new ContractSnapshot.Terms(
                UUID.randomUUID(),
                LocalDate.of(2026, 1, 1),
                new BigDecimal("31.00"),
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                new BigDecimal("21"),
                14)));
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Autowired lt.printerrental.billing.application.InvoiceService invoices;
  @Autowired lt.printerrental.settlement.application.SettlementService payments;
  @Autowired lt.printerrental.integration.DurableEventInbox inbox;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  UUID contract;
  ContractSnapshot source;

  @BeforeEach
  void setup() {
    Time.now = Instant.parse("2026-04-06T12:00:00Z");
    db.execute(
        "TRUNCATE"
            + " billing.contracts,billing.customer_accounts,billing.outbox_events,billing.inbox_events"
            + " CASCADE");
    as("employee", "EMPLOYEE");
    contract = UUID.randomUUID();
    source = snapshot(contract);
    projections.receive(source);
  }

  lt.printerrental.billing.application.InvoiceService.View issue(int month) {
    return invoices.issue(
        contract,
        new lt.printerrental.billing.application.InvoiceService.Issue(
            UUID.randomUUID(), YearMonth.of(2026, month)));
  }

  void reading(LocalDate date, long mono, long revision) {
    inbox.accept(
        new lt.printerrental.events.DomainEvent(
            UUID.randomUUID(),
            1,
            "MeterReadingAccepted",
            Time.now,
            contract,
            "alice",
            Map.of(
                "readingId",
                UUID.randomUUID().toString(),
                "asOf",
                date.toString(),
                "counters",
                List.of(mono, 0L, 0L, 0L),
                "streamRevision",
                revision)));
  }

  @Test
  void missingHistoryBillsFixedOnlyAndObservedUsageIsReconciledOnNewInvoice() {
    var january = issue(1);
    assertThat(january.invoice().calculation().preliminary()).isTrue();
    assertThat(january.invoice().amountDue()).isEqualByComparingTo("37.51");
    reading(LocalDate.of(2026, 1, 31), 1000, 1);
    assertThat(payments.balance("alice").unbilledDebit()).isEqualByComparingTo("12.10");
    var february = issue(2);
    assertThat(february.invoice().debitApplied()).isEqualByComparingTo("12.10");
    assertThat(invoices.view(january.invoice().id()).invoice().calculation())
        .isEqualTo(january.invoice().calculation());
    assertThat(invoices.view(january.invoice().id()).invoice().amountDue())
        .isEqualByComparingTo("37.51");
  }

  @Test
  void overestimatedPagesProduceCarryForwardCreditNotFakeCash() {
    reading(LocalDate.of(2026, 1, 31), 31000, 1);
    issue(1);
    var february = issue(2);
    reading(LocalDate.of(2026, 3, 31), 31001, 2);
    var before = payments.balance("alice");
    assertThat(before.correctionCredit()).isEqualByComparingTo("338.80");
    assertThat(before.received()).isZero();
    var march = issue(3);
    assertThat(march.invoice().amountDue()).isZero();
    assertThat(march.status()).isEqualTo("NO_PAYMENT_DUE");
    assertThat(payments.balance("alice").correctionCredit()).isEqualByComparingTo("301.28");
    assertThat(invoices.view(february.invoice().id()).invoice().calculation().usageNet())
        .isEqualByComparingTo("280.00");
  }

  @Test
  void paymentIsIdempotentAllocatesOldestAndPreservesAdvanceThenReversalAudit() {
    var jan = issue(1);
    var feb = issue(2);
    var key = UUID.randomUUID();
    var input =
        new lt.printerrental.settlement.application.SettlementService.Register(
            key, "alice", new BigDecimal("100.00"), LocalDate.of(2026, 4, 1), "bank-ref-1");
    payments.register(input);
    payments.register(input);
    assertThat(payments.balance("alice").outstanding()).isZero();
    assertThat(payments.balance("alice").advance()).isEqualByComparingTo("24.98");
    assertThat(db.queryForObject("SELECT count(*) FROM billing.payments", Long.class)).isEqualTo(1);
    assertThatThrownBy(
            () ->
                payments.register(
                    new lt.printerrental.settlement.application.SettlementService.Register(
                        key,
                        "alice",
                        new BigDecimal("101.00"),
                        LocalDate.of(2026, 4, 1),
                        "bank-ref-1")))
        .isInstanceOf(lt.printerrental.api.ApiProblem.class);
    var version = payments.balance("alice").version();
    var reversal =
        new lt.printerrental.settlement.application.SettlementService.Reverse(
            UUID.randomUUID(), version, "Wrong bank transaction");
    payments.reverse(key, reversal);
    payments.reverse(key, reversal);
    assertThat(payments.balance("alice").outstanding()).isEqualByComparingTo("75.02");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM billing.payment_allocations WHERE reversed_at IS NOT NULL",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void partialPaymentCoversJanuaryBeforeFebruary() {
    var jan = issue(1);
    var feb = issue(2);
    payments.register(
        new lt.printerrental.settlement.application.SettlementService.Register(
            UUID.randomUUID(),
            "alice",
            new BigDecimal("40.00"),
            LocalDate.of(2026, 4, 1),
            "bank-ref-2"));
    assertThat(invoices.view(jan.invoice().id()).outstanding()).isZero();
    assertThat(invoices.view(feb.invoice().id()).paid()).isEqualByComparingTo("2.49");
  }

  @Test
  void simultaneousPaymentsNeverOverallocateInvoiceDebt() throws Exception {
    issue(1);
    issue(2);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int n = 0; n < 2; n++)
        futures.add(
            pool.submit(
                () -> {
                  as("employee", "EMPLOYEE");
                  gate.await();
                  try {
                    payments.register(
                        new lt.printerrental.settlement.application.SettlementService.Register(
                            UUID.randomUUID(),
                            "alice",
                            new BigDecimal("70.00"),
                            LocalDate.of(2026, 4, 1),
                            "parallel-transfer"));
                    return null;
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      gate.countDown();
      for (var f : futures) f.get();
      assertThat(payments.balance("alice").outstanding()).isZero();
      assertThat(payments.balance("alice").advance()).isEqualByComparingTo("64.98");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void pendingReadingBeforeContractIsDurableAndReplayedAfterSnapshot() {
    var unknown = UUID.randomUUID();
    inbox.accept(
        new lt.printerrental.events.DomainEvent(
            UUID.randomUUID(),
            1,
            "MeterReadingAccepted",
            Time.now,
            unknown,
            "alice",
            Map.of(
                "readingId",
                UUID.randomUUID().toString(),
                "asOf",
                "2026-01-31",
                "counters",
                List.of(10L, 0L, 0L, 0L),
                "streamRevision",
                1)));
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM billing.inbox_events WHERE processed_at IS NULL", Long.class))
        .isEqualTo(1);
    projections.receive(snapshot(unknown));
    inbox.retryPending();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM billing.inbox_events WHERE processed_at IS NULL", Long.class))
        .isZero();
  }

  @Test
  void duplicateReadingEventCannotDuplicateCorrection() {
    var invoice = issue(1);
    var event =
        new lt.printerrental.events.DomainEvent(
            UUID.randomUUID(),
            1,
            "MeterReadingAccepted",
            Time.now,
            contract,
            "alice",
            Map.of(
                "readingId",
                UUID.randomUUID().toString(),
                "asOf",
                "2026-01-31",
                "counters",
                List.of(1000L, 0L, 0L, 0L),
                "streamRevision",
                1));
    inbox.accept(event);
    inbox.accept(event);
    assertThat(db.queryForObject("SELECT count(*) FROM billing.usage_adjustments", Long.class))
        .isEqualTo(1);
  }

  @Test
  void customerCannotRegisterPaymentOrReadOtherCustomersAccount() {
    as("alice", "CUSTOMER");
    assertThatThrownBy(
            () ->
                payments.register(
                    new lt.printerrental.settlement.application.SettlementService.Register(
                        UUID.randomUUID(),
                        "alice",
                        new BigDecimal("10.00"),
                        LocalDate.of(2026, 4, 1),
                        "blocked-payment")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    as("bob", "CUSTOMER");
    assertThatThrownBy(() -> payments.balance("alice"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void debtBecomesOverdueOnlyAfterStoredPaymentDeadline() {
    var invoice = issue(1);
    assertThat(invoice.invoice().dueOn()).isEqualTo(LocalDate.of(2026, 4, 20));
    Time.now = Instant.parse("2026-04-20T12:00:00Z");
    assertThat(payments.balance("alice").overdue()).isZero();
    Time.now = Instant.parse("2026-04-21T12:00:00Z");
    assertThat(payments.balance("alice").overdue()).isEqualByComparingTo("37.51");
  }

  @Test
  void rollbackCannotLeavePaymentOrOutboxWithoutAllocation() {
    issue(1);
    db.update("DELETE FROM billing.outbox_events");
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              payments.register(
                  new lt.printerrental.settlement.application.SettlementService.Register(
                      UUID.randomUUID(),
                      "alice",
                      new BigDecimal("10.00"),
                      LocalDate.of(2026, 4, 1),
                      "rollback-transfer"));
              tx.setRollbackOnly();
            });
    assertThat(db.queryForObject("SELECT count(*) FROM billing.payments", Long.class)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM billing.payment_allocations", Long.class))
        .isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM billing.outbox_events", Long.class))
        .isZero();
  }

  @Test
  void databasePreventsIssuedDocumentRewriting() {
    var invoice = issue(1);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE billing.invoices SET amount_due=0 WHERE id=?", invoice.invoice().id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(invoices.view(invoice.invoice().id()).invoice().amountDue())
        .isEqualByComparingTo("37.51");
  }

  @Test
  void completionProjectionDoesNotPoisonInboxWhenLaterReadingsAlreadyExist() {
    reading(LocalDate.of(2026, 1, 31), 310, 1);
    issue(1);
    var ended =
        new ContractSnapshot(
            source.contractId(),
            source.printerId(),
            source.ownerSubject(),
            source.modelKey(),
            true,
            true,
            "COMPLETED",
            source.startDate(),
            source.endDate(),
            LocalDate.of(2026, 1, 15),
            source.baselineDate(),
            source.baseline(),
            2,
            source.terms());
    projections.receive(ended);
    assertThat(streams.find(contract).orElseThrow().completedReadingDate())
        .isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(db.queryForObject("SELECT count(*) FROM billing.reading_projections", Long.class))
        .isEqualTo(1);
  }

  @Test
  void aNeonAdminCannotInjectPrivateBrokerEvents() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/internal/events")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }
}
