-- Do not delete or merge financial records automatically. If legacy duplicates exist,
-- stop migration so an operator can review them before enforcing this invariant.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM invoices WHERE kind='RECONCILIATION'
             GROUP BY contract_id HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'Duplicate final reconciliations require accounting review before migration';
  END IF;
END $$;
CREATE UNIQUE INDEX final_reconciliation_once ON invoices(contract_id) WHERE kind='RECONCILIATION';
