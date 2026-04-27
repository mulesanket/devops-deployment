import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { useAuth } from './AuthContext'
import { cartApi } from '../api/cart'

const CartContext = createContext(null)

export function CartProvider({ children }) {
  const { isAuthenticated } = useAuth()
  const [cart, setCart] = useState(null)
  const [loading, setLoading] = useState(false)

  const fetchCart = useCallback(async () => {
    if (!isAuthenticated) {
      setCart(null)
      return
    }
    setLoading(true)
    try {
      const data = await cartApi.getCart()
      setCart(data)
    } catch (err) {
      console.error('Failed to fetch cart:', err)
    } finally {
      setLoading(false)
    }
  }, [isAuthenticated])

  useEffect(() => {
    fetchCart()
  }, [fetchCart])

  const addToCart = async (product) => {
    const data = await cartApi.addToCart({
      productId: product.id,
      productName: product.name,
      imageUrl: product.imageUrl,
      price: product.price,
      quantity: 1,
    })
    setCart(data)
  }

  const updateQuantity = async (itemId, quantity) => {
    const data = await cartApi.updateQuantity(itemId, quantity)
    setCart(data)
  }

  const removeItem = async (itemId) => {
    const data = await cartApi.removeItem(itemId)
    setCart(data)
  }

  const clearCart = async () => {
    await cartApi.clearCart()
    setCart({ ...cart, items: [], totalPrice: 0, totalItems: 0 })
  }

  const cartCount = cart?.totalItems || 0

  return (
    <CartContext.Provider value={{ cart, loading, cartCount, addToCart, updateQuantity, removeItem, clearCart, fetchCart }}>
      {children}
    </CartContext.Provider>
  )
}

export const useCart = () => {
  const context = useContext(CartContext)
  if (!context) throw new Error('useCart must be used within CartProvider')
  return context
}
