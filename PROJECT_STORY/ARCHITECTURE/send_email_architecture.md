# User Signup Flow

For user signup, the request started from the React frontend.

When a new user submitted the signup form, the frontend called the backend signup API. The backend API was running as a Spring Boot microservice inside EKS.

After validating the request and storing required user details in Aurora PostgreSQL, the backend triggered an asynchronous notification flow using AWS managed services.

Instead of sending the email directly from the backend synchronously, we used SNS, SQS, Lambda, and SES.

The backend published a signup event to SNS. SNS then delivered the event to SQS. SQS acted as a buffer and decoupled the backend service from the email processing logic.

A Lambda function consumed the message from SQS, prepared the required email content, and used Amazon SES to send signup confirmation or invitation emails to the user.

This helped us keep the signup API fast and reliable because the user creation request did not have to wait for the email sending process to complete.

## Signup flow diagram:

```text
User
 |
 | 1. Opens signup page
 v
CloudFront + WAF
 |
 | 2. Loads React frontend from S3
 v
Browser
 |
 | 3. Submits signup form
 v
CloudFront + WAF
 |
 | 4. Routes API request to backend origin
 v
ALB / Kubernetes Ingress
 |
 | 5. Routes to signup microservice
 v
Spring Boot Signup Service on EKS
 |
 | 6. Validates user and stores user data
 v
Aurora Serverless PostgreSQL v2
 |
 | 7. Publishes signup event
 v
SNS Topic
 |
 | 8. Delivers message to queue
 v
SQS Queue
 |
 | 9. Lambda consumes message
 v
Lambda Function
 |
 | 10. Sends email
 v
Amazon SES
 |
 | 11. User receives signup/invite email
 v
User Email Inbox
```

## Why this design was used:

1. Decoupling
   Backend signup service was not tightly coupled with email sending logic.

2. Better user experience
   Signup API response was faster because it did not wait for SES email delivery.

3. Reliability
   SQS provided buffering and retry support.

4. Scalability
   Lambda could process signup messages independently based on event volume.

5. Separation of responsibility
   Backend handled user creation, while Lambda handled notification processing.
