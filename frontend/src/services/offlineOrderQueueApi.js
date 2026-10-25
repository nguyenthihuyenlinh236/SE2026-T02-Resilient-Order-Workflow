let queueApiPromise

function loadQueueApi() {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('Hàng đợi đơn offline chỉ khả dụng trên trình duyệt.'))
  }

  if (window.OfflineOrderQueue?.createOfflineOrderQueue) {
    return Promise.resolve(window.OfflineOrderQueue.createOfflineOrderQueue())
  }

  if (!queueApiPromise) {
    queueApiPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = '/offline-order-queue.js'
      script.async = true
      script.onload = () => {
        if (!window.OfflineOrderQueue?.createOfflineOrderQueue) {
          queueApiPromise = undefined
          reject(new Error('Không tải được API hàng đợi đơn offline.'))
          return
        }
        resolve(window.OfflineOrderQueue.createOfflineOrderQueue())
      }
      script.onerror = () => {
        queueApiPromise = undefined
        reject(new Error('Không thể tải module hàng đợi đơn offline.'))
      }
      document.head.append(script)
    })
  }

  return queueApiPromise
}

export async function enqueueOfflineOrder(accountId, payload) {
  const queue = await loadQueueApi()
  return queue.enqueue(accountId, payload)
}

export async function listOfflineOrders(accountId) {
  const queue = await loadQueueApi()
  return queue.list(accountId)
}

export async function removeOfflineOrder(idempotencyKey) {
  const queue = await loadQueueApi()
  return queue.remove(idempotencyKey)
}

export async function syncOfflineOrders(accountId) {
  const queue = await loadQueueApi()
  return queue.sync(accountId)
}
