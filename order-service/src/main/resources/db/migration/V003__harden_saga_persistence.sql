ALTER TABLE saga_steps ADD COLUMN saga_payload JSONB;

UPDATE saga_steps
SET saga_payload = request_payload,
    request_payload = request_payload - 'schemaVersion',
    payload_hash = encode(sha256(convert_to((request_payload - 'schemaVersion')::text, 'UTF8')), 'hex')
WHERE operation = 'RESERVE';

ALTER TABLE saga_steps ALTER COLUMN saga_payload SET NOT NULL;

