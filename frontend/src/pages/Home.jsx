import { Link } from 'react-router-dom'
import heroImg from '../assets/images/hero-banner.jpg'
import aboutImg from '../assets/images/about-shopping.jpg'
import catElectronics from '../assets/images/cat-electronics.jpg'
import catFashion from '../assets/images/cat-fashion.jpg'
import catHome from '../assets/images/cat-home.jpg'
import catBeauty from '../assets/images/cat-beauty.jpg'

function Home() {
  return (
    <>
      {/* Hero */}
      <section
        className="hero"
        style={{
          backgroundImage: `linear-gradient(rgba(108,99,255,0.82), rgba(72,198,239,0.78)), url(${heroImg})`,
          backgroundSize: 'cover',
          backgroundPosition: 'center',
        }}
      >
        <h1>Discover Your Style,<br />Shop With Ease</h1>
        <p>
          Premium products at unbeatable prices. Fast delivery, secure payments,
          and an experience crafted just for you.
        </p>
        <Link to="/signup" className="btn-hero">Get Started →</Link>
      </section>

      {/* Categories */}
      <section className="categories" id="categories">
        <h2>Shop by Category</h2>
        <p className="subtitle">Browse our most popular collections</p>
        <div className="categories-grid">
          <div className="category-card">
            <img src={catElectronics} alt="Electronics" />
            <div className="category-overlay"><h3>Electronics</h3></div>
          </div>
          <div className="category-card">
            <img src={catFashion} alt="Fashion" />
            <div className="category-overlay"><h3>Fashion</h3></div>
          </div>
          <div className="category-card">
            <img src={catHome} alt="Home & Living" />
            <div className="category-overlay"><h3>Home &amp; Living</h3></div>
          </div>
          <div className="category-card">
            <img src={catBeauty} alt="Beauty" />
            <div className="category-overlay"><h3>Beauty</h3></div>
          </div>
        </div>
      </section>

      {/* Features */}
      <section className="features" id="features">
        <h2>Why Choose ShopEase?</h2>
        <p className="subtitle">Everything you need for a seamless shopping experience</p>
        <div className="features-grid">
          <div className="feature-card">
            <div className="icon">🚚</div>
            <h3>Free Shipping</h3>
            <p>Enjoy free shipping on all orders over $50. We deliver right to your doorstep.</p>
          </div>
          <div className="feature-card">
            <div className="icon">🔒</div>
            <h3>Secure Payments</h3>
            <p>Your transactions are protected with bank-level encryption and security.</p>
          </div>
          <div className="feature-card">
            <div className="icon">⭐</div>
            <h3>Top Quality</h3>
            <p>Curated selection of premium products from trusted brands worldwide.</p>
          </div>
          <div className="feature-card">
            <div className="icon">🔄</div>
            <h3>Easy Returns</h3>
            <p>Not satisfied? Return any item within 30 days for a full refund.</p>
          </div>
        </div>
      </section>

      {/* About */}
      <section className="about" id="about">
        <div className="about-content">
          <div className="about-text">
            <h2>About ShopEase</h2>
            <p>
              ShopEase was born from a simple idea — shopping should be effortless
              and enjoyable. We connect you with thousands of quality products from
              around the globe, all in one beautiful platform.
            </p>
            <p>
              Our mission is to deliver an unmatched e-commerce experience with
              lightning-fast performance, personalized recommendations, and
              customer service that truly cares.
            </p>
          </div>
          <div className="about-image">
            <img src={aboutImg} alt="About ShopEase" className="about-img" />
          </div>
        </div>
      </section>
    </>
  )
}

export default Home
