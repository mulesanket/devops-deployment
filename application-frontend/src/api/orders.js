const ORDER_API = import.meta.env.VITE_API_BASE_URL || '/api'

async function request(endpoint, options = {}) {
  const token = localStorage.getItem('token')

  const config = {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
      ...options.headers,
    },
  }

  const response = await fetch(`${ORDER_API}${endpoint}`, config)
  const data = await response.json()
  if (!response.ok) throw new Error(data.message || 'Request failed')
  return data
}

export const orderApi = {
  placeOrder: (shippingDetails) => request('/orders', { method: 'POST', body: JSON.stringify(shippingDetails) }),
  getOrders: () => request('/orders'),
  getOrderById: (id) => request(`/orders/${id}`),
  cancelOrder: (id) => request(`/orders/${id}/cancel`, { method: 'PUT' }),
}
