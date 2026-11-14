ALTER TABLE saga_steps
    ADD COLUMN failure_class VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT saga_steps_failure_class_valid CHECK
        (failure_class IN ('NONE','TRANSIENT','BUSINESS','UNKNOWN')),
    ADD CONSTRAINT saga_steps_failure_state_valid CHECK
        ((failure_class = 'TRANSIENT' AND status = 'IN_PROGRESS') OR
         (failure_class = 'BUSINESS' AND status = 'FAILED') OR
         (failure_class = 'UNKNOWN' AND status = 'UNKNOWN') OR
         failure_class = 'NONE');

UPDATE saga_steps SET failure_class='BUSINESS' WHERE status='FAILED';
UPDATE saga_steps SET failure_class='UNKNOWN' WHERE status='UNKNOWN';

