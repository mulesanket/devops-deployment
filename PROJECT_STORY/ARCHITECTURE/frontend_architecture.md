# FRONTEND-FLOW

For the frontend, we used React JS with Vite. React was used to build the user interface for the School Spider portal and related screens, and Vite was used for faster builds and optimized production assets.

Once the frontend build was completed, it generated static files like HTML, CSS, JavaScript, and assets. These files were uploaded to an S3 bucket.

We used CloudFront in front of the S3 bucket to serve the frontend application globally with low latency and caching. AWS WAF was associated with CloudFront to inspect incoming web traffic and protect the application from common web attacks like SQL injection, XSS, and unwanted bot traffic.

So when a user accesses the application URL, the request first reaches CloudFront. WAF checks the request. If the request is allowed, CloudFront serves cached static content. If the content is not available in cache, CloudFront fetches it from the S3 origin and then serves it to the user.

## Frontend flow in simple diagram

```text
User
 |
 | 1. Opens application URL
 v
CloudFront Distribution
 |
 | 2. AWS WAF checks request
 v
Allowed Request
 |
 | 3. CloudFront checks cache
 |
 |-- If cached → returns React static files
 |
 |-- If not cached
 v
S3 Bucket Origin
 |
 | 4. Returns index.html, JS, CSS, assets
 v
Browser loads React app
```

## Why this design was used

We used S3 and CloudFront for frontend hosting because React build output is static. This approach is scalable, cost-effective, and does not require maintaining servers for frontend hosting.

CloudFront improved performance by caching static files at edge locations, and AWS WAF added an additional security layer before traffic reached the application.
