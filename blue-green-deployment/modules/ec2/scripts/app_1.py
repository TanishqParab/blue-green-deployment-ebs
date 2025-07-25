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
                    .app-name { font-size: 36px; font-weight: bold; color: #28a745; }
                </style>
            </head>
            <body>
                <h1>Hello, Blue-Green Deployment on EC2</h1>
                <div class="app-name">APP 1</div>
                <div class="version">V2</div>
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
            "version": "V10",
            "service": "blue-green-app-1"
        }), 200
    except Exception as e:
        return jsonify({
            "status": "unhealthy",
            "error": str(e)
        }), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)