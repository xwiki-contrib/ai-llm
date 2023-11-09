from flask import Flask, request, jsonify
import jwt
from flask_cors import CORS
import requests
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import serialization

app = Flask(__name__)
# CORS(app)  # Enable CORS
CORS(app, origins=[
    "http://appa.local:5000",
    "http://appb.local:5002",
    "http://appafe.local:3000"
])

# Load the public key for JWT validation
def load_public_key_from_file(filename="./public_key.pem"):
    with open(filename, "rb") as key_file:
        return serialization.load_pem_public_key(
            key_file.read(),
            backend=default_backend()
        )

PUBLIC_KEY = load_public_key_from_file()

@app.route('/chat', methods=['POST'])
def chat():
    # Extract the JWT from the Authorization header
    token = request.headers.get('Authorization').split()[1]
    try:
        # Decode the JWT using the public key
        payload = jwt.decode(token, PUBLIC_KEY, algorithms=['RS256'])
        user_id = payload['sub']
        
        # Forward the request to AppB
        appb_response = forward_to_appb(token, request.json)

        # Send AppB's content back to AppA
        return appb_response
    except jwt.ExpiredSignatureError:
        return jsonify({'error': 'Token is expired'}), 401
    except jwt.InvalidTokenError:
        return jsonify({'error': 'Invalid token'}), 401

def forward_to_appb(token, message):
    # Send the JWT and message to AppB
    headers = {'Authorization': f'Bearer {token}'}
    response = requests.post('http://appb.local:5002/content', json=message, headers=headers)
    return response.json()

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5001)
