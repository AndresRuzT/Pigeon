-- Flyway V3: Complete immutable audit protection (statement-level TRUNCATE triggers) and least-privilege role setup

CREATE OR REPLACE FUNCTION prevent_audit_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records and delivery attempts are append-only. TRUNCATE is forbidden.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_audit_truncate();

CREATE TRIGGER trg_delivery_attempt_truncate
    BEFORE TRUNCATE ON delivery_attempt
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_audit_truncate();

-- Enforce least privilege for application user role if configured
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'pigeon_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM pigeon_app;
        REVOKE UPDATE, DELETE, TRUNCATE ON delivery_attempt FROM pigeon_app;
        GRANT SELECT, INSERT ON audit_log TO pigeon_app;
        GRANT SELECT, INSERT ON delivery_attempt TO pigeon_app;
    END IF;
END $$;
