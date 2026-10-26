CREATE TABLE reading_projections (
 contract_id UUID NOT NULL REFERENCES contracts(contract_id),as_of DATE NOT NULL,reading_id UUID NOT NULL,
 counters JSONB NOT NULL,stream_revision BIGINT NOT NULL,PRIMARY KEY(contract_id,as_of)
);
CREATE TABLE customer_accounts(owner_subject VARCHAR(255) PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0);
CREATE TABLE invoices (
 id UUID PRIMARY KEY,owner_subject VARCHAR(255) NOT NULL REFERENCES customer_accounts(owner_subject),
 contract_id UUID NOT NULL REFERENCES contracts(contract_id),period DATE NOT NULL,kind VARCHAR(20) NOT NULL CHECK(kind IN ('MONTHLY','RECONCILIATION')),
 issued_on DATE NOT NULL,due_on DATE NOT NULL,calculation JSONB NOT NULL,
 amount_due NUMERIC(16,2) NOT NULL CHECK(amount_due>=0),credit_applied NUMERIC(16,2) NOT NULL CHECK(credit_applied>=0),
 debit_applied NUMERIC(16,2) NOT NULL CHECK(debit_applied>=0),
 current_usage_net NUMERIC(16,2) NOT NULL,current_usage_vat NUMERIC(16,2) NOT NULL,assessment_revision BIGINT NOT NULL,
 actor VARCHAR(255) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE invoice_usage_assessments(invoice_id UUID PRIMARY KEY REFERENCES invoices(id),usage_net NUMERIC(16,2) NOT NULL,usage_vat NUMERIC(16,2) NOT NULL,revision BIGINT NOT NULL);
CREATE UNIQUE INDEX monthly_invoice_once ON invoices(contract_id,period) WHERE kind='MONTHLY';
CREATE INDEX invoices_fifo_idx ON invoices(owner_subject,due_on,issued_on,id);
CREATE TABLE usage_adjustments (
 id UUID PRIMARY KEY,owner_subject VARCHAR(255) NOT NULL REFERENCES customer_accounts(owner_subject),
 source_invoice UUID NOT NULL REFERENCES invoices(id),source_revision BIGINT NOT NULL,
 net_delta NUMERIC(16,2) NOT NULL,vat_delta NUMERIC(16,2) NOT NULL,remaining_gross NUMERIC(16,2) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),UNIQUE(source_invoice,source_revision)
);
CREATE INDEX adjustments_pending_idx ON usage_adjustments(owner_subject,created_at,id) WHERE remaining_gross<>0;
CREATE TABLE invoice_adjustments(invoice_id UUID NOT NULL REFERENCES invoices(id),adjustment_id UUID NOT NULL REFERENCES usage_adjustments(id),gross_applied NUMERIC(16,2) NOT NULL,PRIMARY KEY(invoice_id,adjustment_id));
CREATE TABLE payments (
 id UUID PRIMARY KEY,owner_subject VARCHAR(255) NOT NULL REFERENCES customer_accounts(owner_subject),
 amount NUMERIC(16,2) NOT NULL CHECK(amount>0),received_on DATE NOT NULL,reference VARCHAR(200) NOT NULL,
 actor VARCHAR(255) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reversed_at TIMESTAMPTZ,reversal_key UUID UNIQUE,reversed_by VARCHAR(255),reversal_reason VARCHAR(2000)
);
CREATE INDEX payments_owner_idx ON payments(owner_subject,received_on,id);
CREATE TABLE payment_allocations (
 id UUID PRIMARY KEY,payment_id UUID NOT NULL REFERENCES payments(id),invoice_id UUID NOT NULL REFERENCES invoices(id),
 amount NUMERIC(16,2) NOT NULL CHECK(amount>0),generation BIGINT NOT NULL,
 allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),reversed_at TIMESTAMPTZ,
 UNIQUE(payment_id,invoice_id,generation)
);
CREATE UNIQUE INDEX active_allocation_once ON payment_allocations(payment_id,invoice_id) WHERE reversed_at IS NULL;
CREATE INDEX allocations_invoice_idx ON payment_allocations(invoice_id) WHERE reversed_at IS NULL;

CREATE FUNCTION forbid_invoice_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Issued invoices are immutable; use a new adjustment'; END $$;
CREATE TRIGGER immutable_invoice BEFORE UPDATE OR DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION forbid_invoice_rewrite();
