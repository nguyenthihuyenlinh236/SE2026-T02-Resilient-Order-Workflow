const ORDERS_URL = '/api/v1/orders'

function pendingStorageKey(accountId) {
  return `resilient-orders-pending:${accountId}`
}

function readPendingRequest(accountId, payload) {
  const storageKey = pendingStorageKey(accountId)
  const payloadJson = JSON.stringify(payload)
  const storedValue = sessionStorage.getItem(storageKey)

  if (storedValue) {
    const pendingRequest = JSON.parse(storedValue)
    if (pendingRequest.payloadJson === payloadJson) {
      return { storageKey, payloadJson, idempotencyKey: pendingRequest.idempotencyKey }
    }
  }

  const pendingRequest = {
    idempotencyKey: crypto.randomUUID(),
    payloadJson,
  }
  sessionStorage.setItem(storageKey, JSON.stringify(pendingRequest))
  return { storageKey, ...pendingRequest }
}

async function getCsrfToken() {
  const response = await fetch('/api/v1/auth/csrf', {
    credentials: 'include',
  })
  if (!response.ok) {
    throw new Error(`Không lấy được mã bảo mật CSRF (${response.status}).`)
  }

  const csrf = await response.json()
  if (!csrf?.headerName || !csrf?.token) {
    throw new Error('Server không trả về CSRF token hợp lệ.')
  }
  return csrf
}

export async function createOrder(accountId, payload) {
  const pendingRequest = readPendingRequest(accountId, payload)
  let response

  try {
    const csrf = await getCsrfToken()
    response = await fetch(ORDERS_URL, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': pendingRequest.idempotencyKey,
        [csrf.headerName]: csrf.token,
      },
      body: pendingRequest.payloadJson,
    })
  } catch (error) {
    throw new Error(
      `Chưa xác nhận được kết quả gửi đơn. Hãy thử lại với cùng nội dung để tránh tạo đơn trùng. ${
        error instanceof Error ? error.message : ''
      }`.trim(),
    )
  }

  if (!response.ok) {
    let message = `Không thể tạo đơn hàng (${response.status}).`
    try {
      const problem = await response.json()
      message = problem.detail || problem.title || message
    } catch {
      // Preserve the status-based message for non-JSON error responses.
    }

    if (response.status < 500 && response.status !== 408 && response.status !== 429) {
      sessionStorage.removeItem(pendingRequest.storageKey)
    }
    throw new Error(message)
  }

  const order = await response.json()
  sessionStorage.removeItem(pendingRequest.storageKey)
  return order
}
