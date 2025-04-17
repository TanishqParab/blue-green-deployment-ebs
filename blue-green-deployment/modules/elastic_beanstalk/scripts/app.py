from flask import Flask, jsonify

app = Flask(__name__)

@app.route('/')
def home():
    return "Hello, Blue-Green Deployment on Amazon Linux 2! Tanishq here New Version v11 v12"

@app.route('/health')
def health():
    """Health check endpoint required for blue-green deployment"""
    try:
        # Add any additional health checks here (database connections, etc.)
        return jsonify({
            "status": "healthy",
            "version": "V10",
            "service": "blue-green-app"
        }), 200
    except Exception as e:
        return jsonify({
            "status": "unhealthy",
            "error": str(e)
        }), 500

# ✅ Add this for WSGI compatibility with Beanstalk
application = app
