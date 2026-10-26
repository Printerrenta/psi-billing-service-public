package lt.printerrental.settlement.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.billing.domain.ChargeCalculator;
import lt.printerrental.settlement.application.*;
import lt.printerrental.settlement.application.LedgerRecords.*;
import lt.printerrental.settlement.domain.PaymentAllocator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLedgerRepository
    implements InvoiceStore, AdjustmentStore, PaymentStore, AccountStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public JdbcLedgerRepository(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public long lockAccount(String owner) {
    db.update(
        "INSERT INTO billing.customer_accounts(owner_subject) VALUES(?) ON CONFLICT DO NOTHING",
        owner);
    return db.queryForObject(
        "SELECT revision FROM billing.customer_accounts WHERE owner_subject=? FOR UPDATE",
        Long.class,
        owner);
  }

  public long bumpAccount(String owner) {
    return db.queryForObject(
        "UPDATE billing.customer_accounts SET revision=revision+1 WHERE owner_subject=? RETURNING"
            + " revision",
        Long.class,
        owner);
  }

  public boolean knownOwner(String owner) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM billing.contracts WHERE owner_subject=?)",
            Boolean.class,
            owner));
  }

  public Optional<Invoice> invoice(UUID id) {
    return queryInvoices("id=?", id).stream().findFirst();
  }

  public Optional<Invoice> monthly(UUID contract, LocalDate period) {
    return queryInvoices("contract_id=? AND period=? AND kind='MONTHLY'", contract, period).stream()
        .findFirst();
  }

  public List<Invoice> issued(UUID contract) {
    return queryInvoices("contract_id=? AND kind='MONTHLY' ORDER BY period", contract);
  }

  public List<Invoice> invoices(String owner, int page, int size) {
    return queryInvoices(
        "owner_subject=? ORDER BY issued_on DESC,id LIMIT ? OFFSET ?",
        owner,
        size,
        Math.multiplyExact(page, size));
  }

  public void insert(Invoice i) {
    db.update(
        "INSERT INTO"
            + " billing.invoices(id,owner_subject,contract_id,period,kind,issued_on,due_on,calculation,amount_due,credit_applied,debit_applied,current_usage_net,current_usage_vat,assessment_revision,actor)"
            + " VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",
        i.id(),
        i.owner(),
        i.contractId(),
        i.period(),
        i.kind(),
        i.issuedOn(),
        i.dueOn(),
        encode(i.calculation()),
        i.amountDue(),
        i.creditApplied(),
        i.debitApplied(),
        i.currentUsageNet(),
        i.currentUsageVat(),
        i.assessmentRevision(),
        i.actor());
    db.update(
        "INSERT INTO billing.invoice_usage_assessments(invoice_id,usage_net,usage_vat,revision)"
            + " VALUES(?,?,?,?)",
        i.id(),
        i.currentUsageNet(),
        i.currentUsageVat(),
        i.assessmentRevision());
  }

  public void reassessed(UUID invoice, BigDecimal net, BigDecimal vat, long revision) {
    db.update(
        "UPDATE billing.invoice_usage_assessments SET usage_net=?,usage_vat=?,revision=? WHERE"
            + " invoice_id=?",
        net,
        vat,
        revision,
        invoice);
  }

  public List<Adjustment> adjustments(String owner) {
    return db.query(
        "SELECT * FROM billing.usage_adjustments WHERE owner_subject=? AND remaining_gross<>0 ORDER"
            + " BY created_at,id",
        (r, n) ->
            new Adjustment(
                r.getObject("id", UUID.class),
                r.getString("owner_subject"),
                r.getObject("source_invoice", UUID.class),
                r.getLong("source_revision"),
                r.getBigDecimal("net_delta"),
                r.getBigDecimal("vat_delta"),
                r.getBigDecimal("remaining_gross")),
        owner);
  }

  public void adjustment(Adjustment a) {
    db.update(
        "INSERT INTO"
            + " billing.usage_adjustments(id,owner_subject,source_invoice,source_revision,net_delta,vat_delta,remaining_gross)"
            + " VALUES(?,?,?,?,?,?,?)",
        a.id(),
        a.owner(),
        a.sourceInvoice(),
        a.sourceRevision(),
        a.netDelta(),
        a.vatDelta(),
        a.remainingGross());
  }

  public void applied(UUID invoice, UUID adjustment, BigDecimal gross, BigDecimal remaining) {
    db.update(
        "INSERT INTO billing.invoice_adjustments(invoice_id,adjustment_id,gross_applied)"
            + " VALUES(?,?,?)",
        invoice,
        adjustment,
        gross);
    db.update(
        "UPDATE billing.usage_adjustments SET remaining_gross=? WHERE id=?", remaining, adjustment);
  }

  public Optional<Payment> payment(UUID id) {
    return queryPayments("id=?", id).stream().findFirst();
  }

  public void payment(Payment p) {
    db.update(
        "INSERT INTO billing.payments(id,owner_subject,amount,received_on,reference,actor)"
            + " VALUES(?,?,?,?,?,?)",
        p.id(),
        p.owner(),
        p.amount(),
        p.receivedOn(),
        p.reference(),
        p.actor());
  }

  public void reverse(UUID id, UUID key, String actor, String reason) {
    db.update(
        "UPDATE billing.payments SET"
            + " reversed_at=now(),reversal_key=?,reversed_by=?,reversal_reason=? WHERE id=?",
        key,
        actor,
        reason,
        id);
  }

  public List<Payment> payments(String owner, int page, int size) {
    return queryPayments(
        "owner_subject=? ORDER BY received_on DESC,id LIMIT ? OFFSET ?",
        owner,
        size,
        Math.multiplyExact(page, size));
  }

  public List<PaymentAllocator.Payment> activePayments(String owner) {
    return db.query(
        "SELECT id,received_on,amount FROM billing.payments WHERE owner_subject=? AND reversed_at"
            + " IS NULL ORDER BY received_on,id",
        (r, n) ->
            new PaymentAllocator.Payment(
                r.getObject(1, UUID.class), r.getObject(2, LocalDate.class), r.getBigDecimal(3)),
        owner);
  }

  public List<PaymentAllocator.Debt> debts(String owner) {
    return db.query(
        "SELECT id,due_on,issued_on,period,amount_due FROM billing.invoices WHERE owner_subject=?"
            + " ORDER BY due_on,issued_on,id",
        (r, n) ->
            new PaymentAllocator.Debt(
                r.getObject(1, UUID.class),
                r.getObject(2, LocalDate.class),
                r.getObject(3, LocalDate.class),
                r.getObject(4, LocalDate.class),
                r.getBigDecimal(5)),
        owner);
  }

  public void allocations(
      String owner, List<PaymentAllocator.Allocation> allocations, long generation) {
    db.update(
        "UPDATE billing.payment_allocations SET reversed_at=now() WHERE reversed_at IS NULL AND"
            + " invoice_id IN (SELECT id FROM billing.invoices WHERE owner_subject=?)",
        owner);
    for (var a : allocations)
      db.update(
          "INSERT INTO billing.payment_allocations(id,payment_id,invoice_id,amount,generation)"
              + " VALUES(?,?,?,?,?)",
          UUID.randomUUID(),
          a.paymentId(),
          a.invoiceId(),
          a.amount(),
          generation);
  }

  public BigDecimal allocated(UUID id) {
    return db.queryForObject(
        "SELECT COALESCE(sum(amount),0) FROM billing.payment_allocations WHERE invoice_id=? AND"
            + " reversed_at IS NULL",
        BigDecimal.class,
        id);
  }

  public Summary summary(String owner, LocalDate today) {
    var invoiced =
        sum(
            "SELECT COALESCE(sum(amount_due),0) FROM billing.invoices WHERE owner_subject=?",
            owner);
    var paid =
        sum(
            "SELECT COALESCE(sum(a.amount),0) FROM billing.payment_allocations a JOIN"
                + " billing.invoices i ON i.id=a.invoice_id WHERE i.owner_subject=? AND"
                + " a.reversed_at IS NULL",
            owner);
    var received =
        sum(
            "SELECT COALESCE(sum(amount),0) FROM billing.payments WHERE owner_subject=? AND"
                + " reversed_at IS NULL",
            owner);
    var overdue =
        sum(
            "SELECT COALESCE(sum(i.amount_due-COALESCE(p.paid,0)),0) FROM billing.invoices i LEFT"
                + " JOIN (SELECT invoice_id,sum(amount) paid FROM billing.payment_allocations WHERE"
                + " reversed_at IS NULL GROUP BY invoice_id) p ON p.invoice_id=i.id WHERE"
                + " i.owner_subject=? AND i.due_on<?",
            owner,
            today);
    var credit =
        sum(
            "SELECT -COALESCE(sum(remaining_gross),0) FROM billing.usage_adjustments WHERE"
                + " owner_subject=? AND remaining_gross<0",
            owner);
    var debit =
        sum(
            "SELECT COALESCE(sum(remaining_gross),0) FROM billing.usage_adjustments WHERE"
                + " owner_subject=? AND remaining_gross>0",
            owner);
    var version =
        db
            .query(
                "SELECT revision FROM billing.customer_accounts WHERE owner_subject=?",
                (r, n) -> r.getLong(1),
                owner)
            .stream()
            .findFirst()
            .orElse(0L);
    return new Summary(
        invoiced,
        invoiced.subtract(paid),
        overdue,
        received,
        received.subtract(paid),
        credit,
        debit,
        version);
  }

  private BigDecimal sum(String sql, Object... args) {
    return db.queryForObject(sql, BigDecimal.class, args).setScale(2);
  }

  private List<Invoice> queryInvoices(String where, Object... args) {
    return db.query(
        "SELECT i.*,a.usage_net AS assessed_net,a.usage_vat AS assessed_vat,a.revision AS"
            + " assessed_revision FROM billing.invoices i JOIN billing.invoice_usage_assessments a"
            + " ON a.invoice_id=i.id WHERE "
            + where,
        (r, n) -> {
          try {
            return new Invoice(
                r.getObject("id", UUID.class),
                r.getString("owner_subject"),
                r.getObject("contract_id", UUID.class),
                r.getObject("period", LocalDate.class),
                r.getString("kind"),
                r.getObject("issued_on", LocalDate.class),
                r.getObject("due_on", LocalDate.class),
                json.readValue(r.getString("calculation"), ChargeCalculator.Calculation.class),
                r.getBigDecimal("amount_due"),
                r.getBigDecimal("credit_applied"),
                r.getBigDecimal("debit_applied"),
                r.getBigDecimal("assessed_net"),
                r.getBigDecimal("assessed_vat"),
                r.getLong("assessed_revision"),
                r.getString("actor"));
          } catch (Exception e) {
            throw new java.sql.SQLException("Invalid invoice", e);
          }
        },
        args);
  }

  private List<Payment> queryPayments(String where, Object... args) {
    return db.query(
        "SELECT * FROM billing.payments WHERE " + where,
        (r, n) ->
            new Payment(
                r.getObject("id", UUID.class),
                r.getString("owner_subject"),
                r.getBigDecimal("amount"),
                r.getObject("received_on", LocalDate.class),
                r.getString("reference"),
                r.getString("actor"),
                r.getTimestamp("reversed_at") == null
                    ? null
                    : r.getTimestamp("reversed_at").toInstant(),
                r.getObject("reversal_key", UUID.class),
                r.getString("reversal_reason")),
        args);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid ledger document");
    }
  }

  public List<AppliedAdjustment> appliedAdjustments(UUID invoice) {
    return db.query(
        "SELECT a.id,a.source_invoice,a.net_delta,a.vat_delta,i.gross_applied FROM"
            + " billing.invoice_adjustments i JOIN billing.usage_adjustments a ON"
            + " a.id=i.adjustment_id WHERE i.invoice_id=? ORDER BY a.created_at,a.id",
        (r, n) ->
            new AppliedAdjustment(
                r.getObject(1, UUID.class),
                r.getObject(2, UUID.class),
                r.getBigDecimal(3),
                r.getBigDecimal(4),
                r.getBigDecimal(5)),
        invoice);
  }

  public List<Adjustment> adjustmentHistory(String owner, int page, int size) {
    return db.query(
        "SELECT * FROM billing.usage_adjustments WHERE owner_subject=? ORDER BY created_at DESC,id"
            + " LIMIT ? OFFSET ?",
        (r, n) ->
            new Adjustment(
                r.getObject("id", UUID.class),
                r.getString("owner_subject"),
                r.getObject("source_invoice", UUID.class),
                r.getLong("source_revision"),
                r.getBigDecimal("net_delta"),
                r.getBigDecimal("vat_delta"),
                r.getBigDecimal("remaining_gross")),
        owner,
        size,
        Math.multiplyExact(page, size));
  }

  public List<AllocationHistory> allocationHistory(String owner, int page, int size) {
    return db.query(
        "SELECT a.* FROM billing.payment_allocations a JOIN billing.payments p ON p.id=a.payment_id"
            + " WHERE p.owner_subject=? ORDER BY a.allocated_at DESC,a.id LIMIT ? OFFSET ?",
        (r, n) ->
            new AllocationHistory(
                r.getObject("id", UUID.class),
                r.getObject("payment_id", UUID.class),
                r.getObject("invoice_id", UUID.class),
                r.getBigDecimal("amount"),
                r.getLong("generation"),
                r.getTimestamp("allocated_at").toInstant(),
                r.getTimestamp("reversed_at") == null
                    ? null
                    : r.getTimestamp("reversed_at").toInstant()),
        owner,
        size,
        Math.multiplyExact(page, size));
  }
}
