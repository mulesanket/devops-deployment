import { useState, useEffect } from 'react'
import { useParams, Link } from 'react-router-dom'
import { productApi } from '../api/products'

function ProductDetail() {
  const { id } = useParams()
  const [product, setProduct] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    loadProduct()
  }, [id])

  const loadProduct = async () => {
    setLoading(true)
    try {
      const data = await productApi.getProductById(id)
      setProduct(data)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  if (loading) return <div className="loading-page">Loading...</div>
  if (error) return <div className="error-page">{error}</div>
  if (!product) return <div className="error-page">Product not found</div>

  return (
    <div className="product-detail-page">
      <div className="product-detail-container">
        <div className="product-detail-image">
          <img src={product.imageUrl || '/images/products/placeholder.jpg'} alt={product.name} />
        </div>
        <div className="product-detail-info">
          <Link to="/products" className="back-link">← Back to Products</Link>
          <span className="product-detail-category">{product.categoryName}</span>
          <h1>{product.name}</h1>
          <p className="product-detail-desc">{product.description}</p>
          <div className="product-detail-price">${product.price}</div>
          <div className="product-detail-stock">
            {product.stock > 0 ? (
              <span className="in-stock">✓ In Stock ({product.stock} available)</span>
            ) : (
              <span className="out-stock">✗ Out of Stock</span>
            )}
          </div>
          <button
            className="btn-add-cart"
            disabled={product.stock === 0}
          >
            {product.stock > 0 ? 'Add to Cart' : 'Out of Stock'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default ProductDetail
