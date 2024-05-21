from flask import Flask, jsonify, request
from flask_cors import CORS
import jwt
from datetime import datetime, timedelta
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend

app = Flask(__name__)
CORS(app, origins=[
    "http://appb.local:5000",
    "http://waise.local:5001",
    "http://appafe.local:3000"
])

# Load the private key for Ed25519
with open("./private.pem", "rb") as key_file:
    private_key = serialization.load_pem_private_key(
        key_file.read(),
        password=None,
        backend=default_backend()
    )

@app.route('/generate_jwt/<username>')
def generate_jwt(username):
    # Expiration time for JWT
    expiration_time = datetime.utcnow() + timedelta(hours=3)
    # Issued at time
    issued_at_time = datetime.utcnow()
    # Not before time
    not_before_time = datetime.utcnow()
    
    # Payload to be encoded
    payload = {
        'sub': username,  # Subject
        'iss': 'http://appafe.local:3000',  # Issuer
        'aud': 'http://waise.local:5001/',  # Audience
        'exp': expiration_time,  # Expiration time
        'iat': issued_at_time,  # Issued at time
        'nbf': not_before_time,  # Not before time
        'given_name': 'Test',  # Optional: First name
        'family_name': 'User',  # Optional: Last name
        'email': 'testuser@example.com',  # Optional: Email
        'groups': ['AuthTestGroup']  # Optional: Groups
    }
    # Generate JWT with Ed25519
    token = jwt.encode(payload, private_key, algorithm='EdDSA')
    return jsonify(jwt=token)

@app.route('/echo_headers', methods=['GET', 'POST', 'OPTIONS'])
def echo_headers():
    return jsonify(dict(request.headers))

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
