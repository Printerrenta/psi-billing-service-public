CREATE INDEX IF NOT EXISTS usage_adjustments_owner_history_idx
    ON usage_adjustments(owner_subject, created_at DESC, id);

CREATE INDEX IF NOT EXISTS invoices_owner_issued_idx
    ON invoices(owner_subject, issued_on DESC, id);
