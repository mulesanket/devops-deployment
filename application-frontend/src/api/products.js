const PRODUCT_API = import.meta.env.VITE_API_BASE_URL || '/api'

async function request(endpoint) {
  const response = await fetch(`${PRODUCT_API}${endpoint}`)
  const data = await response.json()
  if (!response.ok) throw new Error(data.message || 'Request failed')
  return data
}

export const productApi = {
  getAllProducts: () => request('/products'),
  getProductById: (id) => request(`/products/${id}`),
  getProductsByCategory: (categoryId) => request(`/products/category/${categoryId}`),
  searchProducts: (keyword) => request(`/products/search?keyword=${encodeURIComponent(keyword)}`),
  getAllCategories: () => request('/categories'),
}
