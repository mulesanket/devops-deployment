import { useState, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { productApi } from '../api/products'

function Products() {
  const [products, setProducts] = useState([])
  const [categories, setCategories] = useState([])
  const [loading, setLoading] = useState(true)
  const [searchTerm, setSearchTerm] = useState('')
  const [searchParams, setSearchParams] = useSearchParams()
  const activeCategory = searchParams.get('category')

  useEffect(() => {
    loadCategories()
  }, [])

  useEffect(() => {
    loadProducts()
  }, [activeCategory])

  const loadCategories = async () => {
    try {
      const data = await productApi.getAllCategories()
      setCategories(data)
    } catch (err) {
      console.error('Failed to load categories:', err)
    }
  }

  const loadProducts = async () => {
    setLoading(true)
    try {
      const data = activeCategory
        ? await productApi.getProductsByCategory(activeCategory)
        : await productApi.getAllProducts()
      setProducts(data)
    } catch (err) {
      console.error('Failed to load products:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleSearch = async (e) => {
    e.preventDefault()
    if (!searchTerm.trim()) {
      loadProducts()
      return
    }
    setLoading(true)
    try {
      const data = await productApi.searchProducts(searchTerm)
      setProducts(data)
    } catch (err) {
      console.error('Search failed:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleCategoryClick = (categoryId) => {
    setSearchTerm('')
    if (categoryId) {
      setSearchParams({ category: categoryId })
    } else {
      setSearchParams({})
    }
  }

  const getImageUrl = (imageUrl) => {
    // Product images are served from frontend public folder
    return imageUrl || '/images/products/placeholder.jpg'
  }

  return (
    <div className="products-page">
      {/* Header */}
      <div className="products-header">
        <h1>Our Products</h1>
        <p>Discover amazing products across all categories</p>
      </div>

      <div className="products-layout">
        {/* Sidebar */}
        <aside className="products-sidebar">
          <h3>Categories</h3>
          <ul className="category-list">
            <li>
              <button
                className={!activeCategory ? 'active' : ''}
                onClick={() => handleCategoryClick(null)}
              >
                All Products
              </button>
            </li>
            {categories.map((cat) => (
              <li key={cat.id}>
                <button
                  className={activeCategory === String(cat.id) ? 'active' : ''}
                  onClick={() => handleCategoryClick(cat.id)}
                >
                  {cat.name}
                  <span className="cat-count">{cat.productCount}</span>
                </button>
              </li>
            ))}
          </ul>

          {/* Search */}
          <div className="search-box">
            <h3>Search</h3>
            <form onSubmit={handleSearch}>
              <input
                type="text"
                placeholder="Search products..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
              />
              <button type="submit" className="btn-search">Search</button>
            </form>
          </div>
        </aside>

        {/* Product Grid */}
        <main className="products-main">
          {loading ? (
            <div className="loading">Loading products...</div>
          ) : products.length === 0 ? (
            <div className="no-products">
              <p>No products found.</p>
            </div>
          ) : (
            <div className="products-grid">
              {products.map((product) => (
                <Link to={`/products/${product.id}`} key={product.id} className="product-card">
                  <div className="product-img-wrapper">
                    <img src={getImageUrl(product.imageUrl)} alt={product.name} />
                  </div>
                  <div className="product-info">
                    <span className="product-category">{product.categoryName}</span>
                    <h3>{product.name}</h3>
                    <p className="product-desc">{product.description}</p>
                    <div className="product-bottom">
                      <span className="product-price">${product.price}</span>
                      {product.stock > 0 ? (
                        <span className="in-stock">In Stock</span>
                      ) : (
                        <span className="out-stock">Out of Stock</span>
                      )}
                    </div>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </main>
      </div>
    </div>
  )
}

export default Products
