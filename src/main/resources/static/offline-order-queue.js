(function (root, createApi) {
    const api = createApi(root);
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    } else {
        root.OfflineOrderQueue = api;
    }
})(globalThis, function (root) {
    const databaseName = "resilient-order-offline";
    const storeName = "orders";

    function createIndexedDbStorage(indexedDB) {
        if (!indexedDB) {
            throw new Error("IndexedDB is required for offline order storage.");
        }

        let databasePromise;
        function openDatabase() {
            if (!databasePromise) {
                databasePromise = new Promise((resolve, reject) => {
                    const request = indexedDB.open(databaseName, 1);
                    request.onupgradeneeded = () => {
                        const database = request.result;
                        if (!database.objectStoreNames.contains(storeName)) {
                            const store = database.createObjectStore(storeName, {
                                keyPath: "idempotencyKey"
                            });
                            store.createIndex("accountId", "accountId", { unique: false });
                        }
                    };
                    request.onsuccess = () => resolve(request.result);
                    request.onerror = () => reject(request.error);
                }).catch((error) => {
                    databasePromise = undefined;
                    throw error;
                });
            }
            return databasePromise;
        }

        return {
            async list(accountId) {
                const database = await openDatabase();
                return new Promise((resolve, reject) => {
                    const transaction = database.transaction(storeName, "readonly");
                    const request = transaction.objectStore(storeName)
                        .index("accountId")
                        .getAll(accountId);
                    let entries;
                    request.onsuccess = () => {
                        entries = request.result.sort((left, right) =>
                            left.createdAt.localeCompare(right.createdAt)
                        );
                    };
                    request.onerror = () => reject(request.error);
                    transaction.oncomplete = () => resolve(entries);
                    transaction.onerror = () => reject(transaction.error);
                    transaction.onabort = () => reject(transaction.error);
                });
            },
            async put(entry) {
                const database = await openDatabase();
                return new Promise((resolve, reject) => {
                    const transaction = database.transaction(storeName, "readwrite");
                    transaction.objectStore(storeName).put(entry);
                    transaction.oncomplete = () => resolve();
                    transaction.onerror = () => reject(transaction.error);
                    transaction.onabort = () => reject(transaction.error);
                });
            },
            async remove(idempotencyKey) {
                const database = await openDatabase();
                return new Promise((resolve, reject) => {
                    const transaction = database.transaction(storeName, "readwrite");
                    transaction.objectStore(storeName).delete(idempotencyKey);
                    transaction.oncomplete = () => resolve();
                    transaction.onerror = () => reject(transaction.error);
                    transaction.onabort = () => reject(transaction.error);
                });
            }
        };
    }

    function createOfflineOrderQueue(options = {}) {
        const storage = options.storage || createIndexedDbStorage(options.indexedDB || root.indexedDB);
        const fetchImpl = options.fetchImpl || root.fetch;
        const createKey = options.createKey || (() => root.crypto.randomUUID());
        const baseUrl = options.baseUrl || "";

        if (typeof fetchImpl !== "function") {
            throw new Error("Fetch API is required to synchronize offline orders.");
        }

        async function list(accountId) {
            requireAccountId(accountId);
            return storage.list(accountId);
        }

        async function enqueue(accountId, payload) {
            requireAccountId(accountId);
            if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
                throw new TypeError("Order payload must be an object.");
            }

            const entry = {
                idempotencyKey: createKey(),
                accountId,
                payload: JSON.parse(JSON.stringify(payload)),
                createdAt: new Date().toISOString()
            };
            await storage.put(entry);
            return entry;
        }

        async function remove(idempotencyKey) {
            if (typeof idempotencyKey !== "string" || !idempotencyKey.trim()) {
                throw new TypeError("Idempotency key is required.");
            }
            await storage.remove(idempotencyKey);
        }

        async function sync(accountId) {
            requireAccountId(accountId);
            const entries = await storage.list(accountId);
            if (entries.length === 0) {
                return { sent: 0, pending: 0, reason: "empty" };
            }

            let userResponse;
            try {
                userResponse = await fetchImpl(`${baseUrl}/api/v1/auth/me`, {
                    credentials: "include"
                });
            } catch (error) {
                return networkResult(0, entries.length, error);
            }
            if (userResponse.status === 401) {
                return { sent: 0, pending: entries.length, reason: "authentication_required" };
            }
            if (!userResponse.ok) {
                return {
                    sent: 0,
                    pending: entries.length,
                    reason: "server_rejected",
                    status: userResponse.status
                };
            }

            const user = await userResponse.json();
            if (user.accountId !== accountId || user.role !== "CUSTOMER") {
                return { sent: 0, pending: entries.length, reason: "account_mismatch" };
            }

            let csrfResponse;
            try {
                csrfResponse = await fetchImpl(`${baseUrl}/api/v1/auth/csrf`, {
                    credentials: "include"
                });
            } catch (error) {
                return networkResult(0, entries.length, error);
            }
            if (!csrfResponse.ok) {
                return {
                    sent: 0,
                    pending: entries.length,
                    reason: "server_rejected",
                    status: csrfResponse.status
                };
            }
            const csrf = await csrfResponse.json();
            if (!csrf.token || !csrf.headerName) {
                throw new Error("CSRF response did not include a token and header name.");
            }

            let sent = 0;
            const orders = [];
            for (const entry of entries) {
                let orderResponse;
                try {
                    orderResponse = await fetchImpl(`${baseUrl}/api/v1/orders`, {
                        method: "POST",
                        credentials: "include",
                        headers: {
                            "Content-Type": "application/json",
                            "Idempotency-Key": entry.idempotencyKey,
                            [csrf.headerName]: csrf.token
                        },
                        body: JSON.stringify(entry.payload)
                    });
                } catch (error) {
                    return networkResult(sent, entries.length - sent, error);
                }

                if (!orderResponse.ok) {
                    return {
                        sent,
                        pending: entries.length - sent,
                        reason: orderResponse.status === 401
                            ? "authentication_required"
                            : isRetryableStatus(orderResponse.status)
                                ? "retryable_response"
                                : "server_rejected",
                        status: orderResponse.status,
                        idempotencyKey: entry.idempotencyKey
                    };
                }

                const order = await orderResponse.json();
                await storage.remove(entry.idempotencyKey);
                orders.push(order);
                sent += 1;
                if (sent === entries.length) {
                    return { sent, pending: 0, reason: "complete", orders };
                }
            }

            return { sent, pending: entries.length - sent, reason: "complete" };
        }

        return { enqueue, list, remove, sync };
    }

    function requireAccountId(accountId) {
        if (typeof accountId !== "string" || !accountId.trim()) {
            throw new TypeError("Account ID is required.");
        }
    }

    function isRetryableStatus(status) {
        return status === 408 || status === 429 || status >= 500;
    }

    function networkResult(sent, pending, error) {
        return {
            sent,
            pending,
            reason: "network_unavailable",
            message: error instanceof Error ? error.message : String(error)
        };
    }

    return { createOfflineOrderQueue };
});
