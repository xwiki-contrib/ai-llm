from flask import Flask, request, jsonify
import jwt
from flask_cors import CORS
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import serialization

app = Flask(__name__)
CORS(app, origins=[
    "http://appa.local:5000",
    "http://waise.local:5001",
    "http://appafe.local:3000"
])

# Load the public key from a PEM file
def load_public_key_from_file(filename="./public_key.pem"):
    with open(filename, "rb") as key_file:
        return serialization.load_pem_public_key(
            key_file.read(),
            backend=default_backend()
        )

# Init publick key
PUBLIC_KEY = load_public_key_from_file()

# Dummy data for the users
user_content = {
    'user1': 'Content for user 1',
    'user2': 'Content for user 2',
    # Add more users and content as needed
}

@app.route('/content', methods=['POST'])
def content():
    token = request.headers.get('Authorization').split()[1]
    try:
        payload = jwt.decode(token, PUBLIC_KEY, algorithms=['RS256'])
        user_id = payload['sub']
        # Fetch and return the content for the user
        return jsonify({'content': user_content.get(user_id, 'No content available for this user')})
    except jwt.ExpiredSignatureError:
        return jsonify({'error': 'Token is expired'}), 401
    except jwt.InvalidTokenError:
        return jsonify({'error': 'Invalid token'}), 401

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5002)
