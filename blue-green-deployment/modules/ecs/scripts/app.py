from flask import Flask, jsonify

app = Flask(__name__)

@app.route('/')
def home():
    return '''
        <html>
            <head>
                <title>Blue-Green Deployment</title>
                <style>
                    body { font-family: Arial, sans-serif; text-align: center; margin-top: 100px; }
                    .version { font-size: 48px; font-weight: bold; color: #007bff; }
                </style>
            </head>
            <body>
                <h1>Hello, Blue-Green Deployment on ECS</h1>
                <div class="version">V25</div>
            </body>
        </html>
    '''

@app.route('/health')
def health():
    """Health check endpoint required for blue-green deployment"""
    try:
        # Add any additional health checks here (database connections, etc.)
        return jsonify({
            "status": "healthy",
            "version": "V1",
            "service": "blue-green-app"
        }), 200
    except Exception as e:
        return jsonify({
            "status": "unhealthy",
            "error": str(e)
        }), 500

if __name__ == '__main__':
    # Change port to 80 to match the container_port in terraform.tfvars
    app.run(host='0.0.0.0', port=80)
