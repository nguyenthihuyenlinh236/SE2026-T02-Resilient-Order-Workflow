const MAX_VIRTUAL_USERS = 148;

export function loadTestConfig(env = process.env) {
    const targetVirtualUsers = Number(env.LOAD_TEST_TARGET_VUS || MAX_VIRTUAL_USERS);
    const virtualUsers = Number(env.LOAD_TEST_VUS || targetVirtualUsers);
    const durationSeconds = Number(env.LOAD_TEST_DURATION_SECONDS || 120);
    const p95TargetMs = Number(env.LOAD_TEST_P95_MS || 2000);

    if (!Number.isInteger(targetVirtualUsers) || targetVirtualUsers < 1 || targetVirtualUsers > MAX_VIRTUAL_USERS) {
        throw new Error(`LOAD_TEST_TARGET_VUS must be an integer from 1 to ${MAX_VIRTUAL_USERS}.`);
    }
    if (!Number.isInteger(virtualUsers) || virtualUsers < 1 || virtualUsers > MAX_VIRTUAL_USERS) {
        throw new Error(`LOAD_TEST_VUS must be an integer from 1 to ${MAX_VIRTUAL_USERS}.`);
    }
    if (!Number.isInteger(durationSeconds) || durationSeconds < 1) {
        throw new Error("LOAD_TEST_DURATION_SECONDS must be a positive integer.");
    }
    if (!Number.isInteger(p95TargetMs) || p95TargetMs < 1) {
        throw new Error("LOAD_TEST_P95_MS must be a positive integer.");
    }

    return { targetVirtualUsers, virtualUsers, durationSeconds, p95TargetMs };
}
