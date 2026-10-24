import { randomUUID } from "node:crypto";

const baseUrl = (process.env.LOAD_TEST_BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const username = process.env.LOAD_TEST_USERNAME;
const password = process.env.LOAD_TEST_PASSWORD;
const sku = process.env.LOAD_TEST_SKU || "DEMO-ITEM";
const virtualUsers = Number(process.env.LOAD_TEST_VUS || 148);
const durationSeconds = Number(process.env.LOAD_TEST_DURATION_SECONDS || 120);
const thinkMinMs = 5000;
const thinkMaxMs = 10000;
const requestTimeoutMs = 10000;
const runId = randomUUID();

if (!username || !password) {
    throw new Error("Set LOAD_TEST_USERNAME and LOAD_TEST_PASSWORD to a CUSTOMER account.");
}
if (!Number.isInteger(virtualUsers) || virtualUsers < 1 || virtualUsers > 148) {
    throw new Error("LOAD_TEST_VUS must be an integer from 1 to 148.");
}
if (!Number.isInteger(durationSeconds) || durationSeconds < 1) {
    throw new Error("LOAD_TEST_DURATION_SECONDS must be a positive integer.");
}

const samples = { post: [], get: [] };
let serverErrors = 0;
let responseCount = 0;
let unexpectedResponses = 0;
let transportErrors = 0;
const unexpectedDetails = [];

function sleep(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function thinkTime() {
    return thinkMinMs + Math.floor(Math.random() * (thinkMaxMs - thinkMinMs + 1));
}

function setCookies(jar, response) {
    for (const cookie of response.headers.getSetCookie()) {
        const separator = cookie.indexOf("=");
        if (separator > 0) {
            jar.set(cookie.slice(0, separator), cookie.slice(separator + 1).split(";")[0]);
        }
    }
}

function cookieHeader(jar) {
    return [...jar].map(([name, value]) => `${name}=${value}`).join("; ");
}

async function request(url, options = {}) {
    return fetch(url, {
        ...options,
        signal: AbortSignal.timeout(requestTimeoutMs)
    });
}

async function loginVirtualUser(index) {
    const jar = new Map();
    const csrfResponse = await request(`${baseUrl}/api/v1/auth/csrf`);
    setCookies(jar, csrfResponse);
    if (!csrfResponse.ok) {
        throw new Error(`Virtual user ${index}: CSRF request returned HTTP ${csrfResponse.status}.`);
    }
    const csrf = await csrfResponse.json();

    const loginResponse = await request(`${baseUrl}/api/v1/auth/login`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Cookie: cookieHeader(jar),
            [csrf.headerName]: csrf.token
        },
        body: JSON.stringify({ username, password })
    });
    setCookies(jar, loginResponse);
    if (!loginResponse.ok) {
        throw new Error(`Virtual user ${index}: login returned HTTP ${loginResponse.status}.`);
    }

    const account = await loginResponse.json();
    if (account.role !== "CUSTOMER") {
        throw new Error(`Virtual user ${index}: LOAD_TEST_USERNAME must belong to a CUSTOMER account.`);
    }
    return { index, cookies: cookieHeader(jar), csrf };
}

async function measure(kind, expectedStatus, operation) {
    const startedAt = performance.now();
    try {
        const response = await operation();
        const durationMs = performance.now() - startedAt;
        responseCount += 1;
        if (response.status >= 500) {
            serverErrors += 1;
        }
        if (response.status !== expectedStatus) {
            unexpectedResponses += 1;
            unexpectedDetails.push({ kind, status: response.status });
        }
        samples[kind].push({ durationMs, status: response.status });
        return response;
    } catch (error) {
        const durationMs = performance.now() - startedAt;
        transportErrors += 1;
        unexpectedResponses += 1;
        unexpectedDetails.push({
            kind,
            error: error instanceof Error ? error.message : String(error)
        });
        samples[kind].push({
            durationMs,
            status: 0,
            error: error instanceof Error ? error.message : String(error)
        });
        return null;
    }
}

async function runVirtualUser(user, deadline) {
    await sleep(Math.floor(Math.random() * thinkMaxMs));
    let sequence = 0;
    while (Date.now() < deadline) {
        sequence += 1;
        const idempotencyKey = `${runId}-${user.index}-${sequence}`;
        const created = await measure("post", 202, () => request(`${baseUrl}/api/v1/orders`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Cookie: user.cookies,
                [user.csrf.headerName]: user.csrf.token,
                "Idempotency-Key": idempotencyKey
            },
            body: JSON.stringify({ items: [{ sku, quantity: 1 }] })
        }));

        if (created?.status === 202) {
            try {
                const order = await created.json();
                if (!order.orderId) {
                    unexpectedResponses += 1;
                    unexpectedDetails.push({ kind: "post", error: "Response did not contain orderId." });
                } else {
                    await sleep(thinkTime());
                    await measure("get", 200, async () => {
                        const response = await request(`${baseUrl}/api/v1/orders/${order.orderId}`, {
                            headers: { Cookie: user.cookies }
                        });
                        if (response.status === 200) {
                            const retrieved = await response.clone().json();
                            if (retrieved.orderId !== order.orderId) {
                                unexpectedResponses += 1;
                                unexpectedDetails.push({
                                    kind: "get",
                                    error: "Retrieved order ID did not match the submitted order."
                                });
                            }
                        }
                        return response;
                    });
                }
            } catch (error) {
                unexpectedResponses += 1;
                unexpectedDetails.push({
                    kind: "post",
                    error: error instanceof Error ? error.message : String(error)
                });
            }
        }
        await sleep(thinkTime());
    }
}

function percentile(values, percentileValue) {
    if (values.length === 0) {
        return null;
    }
    const sorted = [...values].sort((left, right) => left - right);
    return Math.round(sorted[Math.ceil(percentileValue * sorted.length) - 1]);
}

async function main() {
    const users = await Promise.all(
        Array.from({ length: virtualUsers }, (_, index) => loginVirtualUser(index + 1))
    );

    const startedAt = Date.now();
    const deadline = startedAt + durationSeconds * 1000;
    await Promise.all(users.map((user) => runVirtualUser(user, deadline)));
    const elapsedSeconds = (Date.now() - startedAt) / 1000;
    const totalSamples = samples.post.length + samples.get.length;
    const errorRate5xx = responseCount === 0 ? 0 : serverErrors / responseCount;

    const report = {
        target: {
            baseUrl,
            virtualUsers,
            durationSeconds,
            thinkTimeMs: [thinkMinMs, thinkMaxMs],
            sku
        },
        measured: {
            elapsedSeconds: Number(elapsedSeconds.toFixed(2)),
            requests: totalSamples,
            throughputPerSecond: Number((totalSamples / elapsedSeconds).toFixed(2)),
            responses5xx: serverErrors,
            errorRate5xx: Number(errorRate5xx.toFixed(4)),
            transportErrors,
            unexpectedResponses,
            post: {
                requests: samples.post.length,
                p95Ms: percentile(samples.post.map((sample) => sample.durationMs), 0.95)
            },
            get: {
                requests: samples.get.length,
                p95Ms: percentile(samples.get.map((sample) => sample.durationMs), 0.95)
            }
        },
        thresholds: {
            targetVirtualUsers: 148,
            minimumDurationSeconds: 120,
            postP95Ms: 2000,
            getP95Ms: 2000,
            errorRate5xx: 0.01
        },
        unexpectedDetails
    };

    console.log(JSON.stringify(report, null, 2));
    if (
        virtualUsers !== report.thresholds.targetVirtualUsers
        || durationSeconds < report.thresholds.minimumDurationSeconds
        || report.measured.post.p95Ms === null
        || report.measured.get.p95Ms === null
        || report.measured.post.p95Ms > report.thresholds.postP95Ms
        || report.measured.get.p95Ms > report.thresholds.getP95Ms
        || report.measured.errorRate5xx > report.thresholds.errorRate5xx
        || report.measured.unexpectedResponses > 0
    ) {
        process.exitCode = 1;
    }
}

await main();
