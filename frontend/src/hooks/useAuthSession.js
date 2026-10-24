import { useCallback, useEffect, useState } from 'react'
import { AuthApiError, authApi } from '../services/authApi.js'

export function useAuthSession() {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true

    authApi.getCurrentUser()
      .then((currentUser) => {
        if (active && currentUser.role === 'CUSTOMER') {
          setUser(currentUser)
        }
      })
      .catch((requestError) => {
        if (active && !(requestError instanceof AuthApiError && requestError.status === 401)) {
          setError(requestError.message)
        }
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
    }
  }, [])

  const login = useCallback(async (username, password) => {
    setError('')
    const currentUser = await authApi.login(username, password)

    if (currentUser.role !== 'CUSTOMER') {
      await authApi.logout()
      throw new Error('Giao diện này dành cho tài khoản khách hàng.')
    }

    setUser(currentUser)
  }, [])

  const logout = useCallback(async () => {
    setError('')
    await authApi.logout()
    setUser(null)
  }, [])

  return { user, loading, error, setError, login, logout }
}
