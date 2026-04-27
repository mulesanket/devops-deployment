import json
import boto3

ses_client = boto3.client('ses', region_name='ap-south-1')
SENDER_EMAIL = 'sanketsp60@gmail.com'

def lambda_handler(event, context):
    for record in event['Records']:
        # SQS message body contains the SNS message
        sns_message = json.loads(record['body'])
        # The actual payload is inside 'Message'
        payload = json.loads(sns_message['Message'])
        
        user_name = payload['name']
        user_email = payload['email']
        
        send_welcome_email(user_name, user_email)
    
    return {'statusCode': 200, 'body': 'Emails sent'}


def send_welcome_email(name, email):
    subject = '🎉 Welcome to ShopEase!'
    
    html_body = f"""
    <!DOCTYPE html>
    <html>
    <head>
        <style>
            body {{ font-family: 'Segoe UI', Arial, sans-serif; margin: 0; padding: 0; background: #f4f4f8; }}
            .container {{ max-width: 600px; margin: 40px auto; background: #fff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.08); }}
            .header {{ background: linear-gradient(135deg, #6c63ff 0%, #48c6ef 100%); padding: 40px 30px; text-align: center; }}
            .header h1 {{ color: #fff; margin: 0; font-size: 28px; }}
            .header p {{ color: rgba(255,255,255,0.9); margin: 8px 0 0; font-size: 16px; }}
            .body {{ padding: 35px 30px; }}
            .body h2 {{ color: #1a1a2e; font-size: 22px; margin-bottom: 15px; }}
            .body p {{ color: #555; font-size: 15px; line-height: 1.7; }}
            .features {{ display: flex; gap: 15px; margin: 25px 0; flex-wrap: wrap; }}
            .feature {{ flex: 1; min-width: 120px; background: #f8f7ff; padding: 18px 12px; border-radius: 12px; text-align: center; }}
            .feature .icon {{ font-size: 28px; margin-bottom: 6px; }}
            .feature .label {{ font-size: 13px; color: #333; font-weight: 600; }}
            .cta {{ display: inline-block; background: #6c63ff; color: #fff; padding: 14px 36px; border-radius: 10px; text-decoration: none; font-weight: 700; font-size: 15px; margin-top: 10px; }}
            .footer {{ background: #f8f7ff; padding: 20px 30px; text-align: center; font-size: 13px; color: #999; }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h1>Welcome to ShopEase! 🛍️</h1>
                <p>Your journey to great shopping starts here</p>
            </div>
            <div class="body">
                <h2>Hi {name}! 👋</h2>
                <p>We're thrilled to have you on board. Your account has been successfully created and you're all set to explore thousands of amazing products.</p>
                
                <div class="features">
                    <div class="feature">
                        <div class="icon">🚚</div>
                        <div class="label">Free Shipping</div>
                    </div>
                    <div class="feature">
                        <div class="icon">🔒</div>
                        <div class="label">Secure Pay</div>
                    </div>
                    <div class="feature">
                        <div class="icon">⭐</div>
                        <div class="label">Top Quality</div>
                    </div>
                    <div class="feature">
                        <div class="icon">🔄</div>
                        <div class="label">Easy Returns</div>
                    </div>
                </div>
                
                <p>Start browsing our curated collections and find something you love!</p>
                <a href="#" class="cta">Start Shopping →</a>
            </div>
            <div class="footer">
                <p>© 2026 ShopEase. All rights reserved.</p>
                <p>You received this email because you signed up at ShopEase.</p>
            </div>
        </div>
    </body>
    </html>
    """
    
    text_body = f"Hi {name}! Welcome to ShopEase. Your account has been created successfully. Start shopping now!"
    
    ses_client.send_email(
        Source=SENDER_EMAIL,
        Destination={'ToAddresses': [email]},
        Message={
            'Subject': {'Data': subject, 'Charset': 'UTF-8'},
            'Body': {
                'Html': {'Data': html_body, 'Charset': 'UTF-8'},
                'Text': {'Data': text_body, 'Charset': 'UTF-8'}
            }
        }
    )
    
    print(f"Welcome email sent to {email}")
