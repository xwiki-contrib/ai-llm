# Cross-Origin Resource Sharing (CORS) Demo with JWT Authentication

Disclaimer: This is only a crude example for illustrating the potential  authentication/authorization flow and is not meant to be used as is.

This demo showcases a simple authentication/authorization flow using JSON Web Tokens (JWT) across three separate applications:

- **app_a.py** and **index.html**: An example application with crude front-end interface for authentication and chat; and a back-end for generating the JWT
- **waise.py**: A server responsible for validating JWTs and forwarding requests to **app_b.py**.
- **app_b.py**: A back-end server that holds dummy data and serves content based on user permissions.

**How this example work in a bit more detail:**
- The public key is shared a priori with **waise** and **app_b**
- When the user authenticates using the the front-end interface of **app_a** (the index.html file in this example), the **app_a back-end** generates a JWT that will be included in the headers of subsequent requests sent using the chat interface.
- The **wasie** server checks the validity of the JWT and in the positive case forwards it to **app_b** which holds the data to be retrieved (otherwise it will respond with an "Invalid token" error message).
- **app_b** then also validates the JWT based on which will return the content authorized for that particular user, or with an appropriate message if no content is available.

## Using the Demo

1. Open the front-end application by navigating to `http://appafe.local:3000` in your web browser.
2. Log in using the basic authentication interface.
3. After logging in, use the chat interface to send messages. The messages will be sent to the Waise Server, which will validate the JWT and forward the message to AppB.
4. AppB will validate the JWT and return the corresponding content if the user is authorized.

## Prerequisites

- Python 3.6 or higher
- A web browser (Firefox recommended due to Chrome's strict HTTPS policy)
- miniconda/anaconda (optional)
## Setup

Clone the repository using:

```bash
git clone git@github.com:xwiki-contrib/application-ai-llm.git
```

and navigate into the application-ai-llm/examples/auth folder:

```bash
cd application-ai-llm/examples/auth
```

### Modify `/etc/hosts`

To simulate a cross-origin environment on your local machine (on unix based systems), add the following lines to your `/etc/hosts` file:

```perl
127.0.0.1 appafe.local # for the front end of AppA
127.0.0.1 appa.local # the back-end of AppA
127.0.0.1 waise.local # the wise server
127.0.0.1 appb.local # AppB
```

For testing with an external IP, replace `127.0.0.1` with your external IP address (but additional setup may be required).

## Installing the required libraries

Navigate to the cloned directory and install the required Python packages (using a conda environment is encouraged but not strictly necessary):

Creating the conda environment :
```shell
conda create -n testauth python=3.9
```

Activating the environment:
```shell
conda activate testauth
```

```bash
pip install requests flask flask-cors PyJWT cryptography
```

## Generate a privet-public key pair

You can generate a new RSA private key using the `openssl` command line tool with the following command:

```bash
openssl genpkey -algorithm ed25519 -outform PEM -out private.pem
```
This will generate a new RSA private key and save it in a file called `private_key.pem`.

Once you have the private key, you can extract the public key from it using:

```bash
openssl pkey -in private.pem -pubout -outform PEM -out public.pem
```

## Execute run.py

Run the three back-end servers with one command:

```sh
python3 run.py
```

This will start the back-end servers for AppA ( http://appa.local:5000 ) , waise ( http://waise.local:5001 ), AppB ( http://appb.local:5002 ) 

We also need to server the front end, so open another terminal and navigate to the same directory (application-ai-llm/examples/auth). From there you can run:

```sh
python3 -m http.server 3000 --bind appafe.local
```

To test the interaction open http://appafe.local:3000 in your browser (recommended Firefox as Chrome has stricter rules regarding https)
Or ideally you can setup https but that is outside the scope of this little example.

## Security Notes

This demo is for demonstration purposes only and does not implement full security measures necessary for a production environment.
