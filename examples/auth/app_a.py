from flask import Flask, jsonify
from flask_cors import CORS
import jwt
from datetime import datetime, timedelta
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
from flask import request


app = Flask(__name__)
CORS(app, origins=[
    "http://appb.local:5000",
    "http://waise.local:5001",
    "http://appafe.local:3000"
])

# This should be your private key
# Load the private key
with open("./private_key.pem", "rb") as key_file:
    private_key = serialization.load_pem_private_key(
        key_file.read(),
        password=None,
        backend=default_backend()
    )

@app.route('/generate_jwt/<username>')
def generate_jwt(username):
    # Expiration time for JWT
    expiration_time = datetime.utcnow() + timedelta(hours=1)
    # Payload to be encoded
    payload = {
        'sub': username,
        'exp': expiration_time,
    }
    # Generate JWT
    token = jwt.encode(payload, private_key, algorithm='RS256')
    return jsonify(jwt=token)

@app.route('/echo_headers', methods=['GET', 'POST', 'OPTIONS'])
def echo_headers():
    return jsonify(dict(request.headers))


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
