import { api } from './client'

export const authApi = {
  signup: (name, email, password) => api.post('/auth/signup', { name, email, password }),
  login: (email, password) => api.post('/auth/login', { email, password }),
  health: () => api.get('/auth/health'),
}
