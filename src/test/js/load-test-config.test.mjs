import assert from "node:assert/strict";
import test from "node:test";
import { loadTestConfig } from "../../../scripts/load-test-config.mjs";

test("retains the team's default load-test targets", () => {
    assert.deepEqual(loadTestConfig({}), {
        targetVirtualUsers: 148,
        virtualUsers: 148,
        durationSeconds: 120,
        p95TargetMs: 2000
    });
});

test("loads the AD-005 concurrency and latency targets", () => {
    assert.deepEqual(loadTestConfig({
        LOAD_TEST_TARGET_VUS: "104",
        LOAD_TEST_P95_MS: "1500"
    }), {
        targetVirtualUsers: 104,
        virtualUsers: 104,
        durationSeconds: 120,
        p95TargetMs: 1500
    });
});

test("rejects invalid target and latency settings", () => {
    assert.throws(() => loadTestConfig({ LOAD_TEST_TARGET_VUS: "149" }), /LOAD_TEST_TARGET_VUS/);
    assert.throws(() => loadTestConfig({ LOAD_TEST_P95_MS: "1.5" }), /LOAD_TEST_P95_MS/);
});
