const AUTH_URL = '/api/v1/auth'

export class AuthApiError extends Error {
  constructor(status, message) {
    super(message)
    this.name = 'AuthApiError'
    this.status = status
  }
}

async function readResponse(response) {
  if (response.ok) {
    if (response.status === 204) return null
    return response.json()
  }

  let message = `Yêu cầu thất bại (${response.status}).`
  try {
    const problem = await response.json()
    message = problem.detail || problem.title || message
  } catch {
    // Keep the HTTP status message when the server response is not JSON.
  }
  throw new AuthApiError(response.status, message)
}

async function getCsrfHeaders() {
  const response = await fetch(`${AUTH_URL}/csrf`, {
    credentials: 'include',
  })
  const csrf = await readResponse(response)

  if (!csrf?.headerName || !csrf?.token) {
    throw new Error('Server không trả về CSRF token hợp lệ.')
  }

  return { [csrf.headerName]: csrf.token }
}

export const authApi = {
  async getCurrentUser() {
    const response = await fetch(`${AUTH_URL}/me`, {
      credentials: 'include',
    })
    return readResponse(response)
  },

  async login(username, password) {
    const csrfHeaders = await getCsrfHeaders()
    const response = await fetch(`${AUTH_URL}/login`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...csrfHeaders,
      },
      body: JSON.stringify({ username, password }),
    })
    return readResponse(response)
  },

  async logout() {
    const csrfHeaders = await getCsrfHeaders()
    const response = await fetch(`${AUTH_URL}/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: csrfHeaders,
    })
    return readResponse(response)
  },
}
