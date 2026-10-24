const assert = require("node:assert/strict");
const { test } = require("node:test");
const { createOfflineOrderQueue } = require("../../main/resources/static/offline-order-queue.js");

function createMemoryStorage() {
    const entries = new Map();
    return {
        async list(accountId) {
            return [...entries.values()]
                .filter((entry) => entry.accountId === accountId)
                .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
        },
        async put(entry) {
            entries.set(entry.idempotencyKey, entry);
        },
        async remove(idempotencyKey) {
            entries.delete(idempotencyKey);
        }
    };
}

function response(body, status = 200) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" }
    });
}

test("queues orders by account and retains a stable idempotency key", async () => {
    const storage = createMemoryStorage();
    let nextKey = 0;
    const queue = createOfflineOrderQueue({
        storage,
        fetchImpl: async () => response({}),
        createKey: () => `order-key-${++nextKey}`
    });

    const first = await queue.enqueue("customer-1", { items: [{ sku: "ITEM", quantity: 1 }] });
    await queue.enqueue("customer-2", { items: [{ sku: "ITEM", quantity: 2 }] });

    assert.equal(first.idempotencyKey, "order-key-1");
    assert.equal((await queue.list("customer-1")).length, 1);
    assert.equal((await queue.list("customer-2")).length, 1);
    assert.equal((await queue.list("customer-1"))[0].idempotencyKey, first.idempotencyKey);
});

test("syncs pending orders with session and CSRF credentials, then removes them", async () => {
    const storage = createMemoryStorage();
    const calls = [];
    const queue = createOfflineOrderQueue({
        storage,
        createKey: (() => {
            let nextKey = 0;
            return () => `order-key-${++nextKey}`;
        })(),
        fetchImpl: async (url, options = {}) => {
            calls.push({ url, options });
            if (url.endsWith("/auth/me")) {
                return response({ accountId: "customer-1", role: "CUSTOMER" });
            }
            if (url.endsWith("/auth/csrf")) {
                return response({ token: "csrf-token", headerName: "X-XSRF-TOKEN" });
            }
            return response({ orderId: `accepted-${calls.length}` }, 202);
        }
    });
    await queue.enqueue("customer-1", { items: [{ sku: "ITEM", quantity: 1 }] });
    await queue.enqueue("customer-1", { items: [{ sku: "ITEM", quantity: 2 }] });

    const result = await queue.sync("customer-1");

    assert.equal(result.reason, "complete");
    assert.equal(result.sent, 2);
    assert.equal(result.orders.length, 2);
    assert.equal((await queue.list("customer-1")).length, 0);
    const orderCalls = calls.filter((call) => call.options.method === "POST");
    assert.equal(orderCalls.length, 2);
    assert.equal(orderCalls[0].options.credentials, "include");
    assert.equal(orderCalls[0].options.headers["Idempotency-Key"], "order-key-1");
    assert.equal(orderCalls[0].options.headers["X-XSRF-TOKEN"], "csrf-token");
});

test("keeps the order queued after a network failure during submission", async () => {
    const storage = createMemoryStorage();
    const queue = createOfflineOrderQueue({
        storage,
        createKey: () => "stable-key",
        fetchImpl: async (url) => {
            if (url.endsWith("/auth/me")) {
                return response({ accountId: "customer-1", role: "CUSTOMER" });
            }
            if (url.endsWith("/auth/csrf")) {
                return response({ token: "csrf-token", headerName: "X-XSRF-TOKEN" });
            }
            throw new Error("Network disconnected");
        }
    });
    await queue.enqueue("customer-1", { items: [{ sku: "ITEM", quantity: 1 }] });

    const result = await queue.sync("customer-1");

    assert.equal(result.reason, "network_unavailable");
    assert.equal(result.pending, 1);
    assert.equal((await queue.list("customer-1"))[0].idempotencyKey, "stable-key");
});

test("does not submit one customer's queue under another account", async () => {
    const storage = createMemoryStorage();
    let orderPostCount = 0;
    const queue = createOfflineOrderQueue({
        storage,
        createKey: () => "stable-key",
        fetchImpl: async (url, options = {}) => {
            if (url.endsWith("/auth/me")) {
                return response({ accountId: "different-customer", role: "CUSTOMER" });
            }
            if (options.method === "POST") {
                orderPostCount += 1;
            }
            return response({ token: "csrf-token", headerName: "X-XSRF-TOKEN" });
        }
    });
    await queue.enqueue("customer-1", { items: [{ sku: "ITEM", quantity: 1 }] });

    const result = await queue.sync("customer-1");

    assert.equal(result.reason, "account_mismatch");
    assert.equal(orderPostCount, 0);
    assert.equal((await queue.list("customer-1")).length, 1);
});
