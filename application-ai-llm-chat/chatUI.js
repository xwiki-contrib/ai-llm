// Load Chat UI
window.onload = () => {
  insertChatUI();
  loadCSS('dist/chat/chat.css');
  loadCSS('chatUI.css')
  loadJSModule('dist/chat/chat.esm.js');

  const fab = document.getElementById('fab');
  const pane = document.getElementById('pane');
  document.getElementById('close').addEventListener('click', () => fab.close());

  XWikiAiAPI.getModels().then(models => console.log(models)).catch(err => console.error(err));
  XWikiAiAPI.getPrompts().then(prompts => console.log(prompts)).catch(err => console.error(err));

  let completionRequest = new ChatCompletionRequest(
      "AI.Models.mixtral", // model
      0.5, // temperature
      [], // messages
      true, // streaming
  )

  async function sendMessageToLLM(completionRequest) {
      let incomingMessage = await pane.addIncomingMessage("");
      // Create separate container for loading animation and text content
      incomingMessage.innerHTML = '<div class="message-text"><div class="waiting-line"><div class="dot dot1"></div><div class="dot dot2"></div><div class="dot dot3"></div></div></div>';

      function removeLoadingAnimation() {
          const loadingSpinner = incomingMessage.querySelector('.waiting-line');
          if (loadingSpinner) {
              loadingSpinner.remove(); // Remove only the loading spinner
          }
      }

      if (completionRequest.stream) {
          XWikiAiAPI.getCompletions(completionRequest, async (messageChunk) => {
              const messageTextContainer = incomingMessage.querySelector('.message-text');
              messageTextContainer.textContent += messageChunk.choices[0].delta.content;
              removeLoadingAnimation(); // Clear loading after updating text to preserve content
          })
          .then(() => {
              completionRequest.addMessage("assistant", incomingMessage.querySelector('.message-text').textContent);
              return completionRequest;
          })
          .catch(err => {
              console.error(err);
              removeLoadingAnimation(); // Ensure to clear loading spinner in case of error
          })
      } else {
          return XWikiAiAPI.getCompletions(completionRequest).then(async messageChunk => {
              removeLoadingAnimation(); // Clear loading before showing the message
              incomingMessage.querySelector('.message-text').textContent = messageChunk.choices[0].message.content;
              completionRequest.addMessage("assistant", incomingMessage.querySelector('.message-text').textContent);
          }).catch(err => {
              console.error(err);
              removeLoadingAnimation(); // Ensure to clear loading spinner in case of error
          }); 
      }
  }

  const wait = () => new Promise(resolve => setTimeout(resolve, 500));

  function handleIncomingMessage(event) {
    let message = event.detail.element;
    wait()
      .then(() => message.state = 'delivered')
      .then(() => completionRequest.addMessage("user", event.detail.text))
      .then(() => message.state = 'read')
      .then(() => sendMessageToLLM(completionRequest));
  }
  pane.addEventListener('incoming', handleIncomingMessage);
}

function loadCSS(href) {
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = href;
  document.head.appendChild(link);
}

function loadJSModule(src) {
  const script = document.createElement('script');
  script.type = 'module';
  script.src = src;
  document.body.appendChild(script);
}

function insertChatUI(){
  const htmlContent = `
  <fab-app id="fab">
    <chat-pane id="pane">
        <ion-toolbar slot="header" color="primary">
          <ion-title>XWiki AI Chat</ion-title>
          <ion-buttons slot="primary">
            <ion-button id="close">
              <ion-icon slot="icon-only" name="close" />
            </ion-button>
          </ion-buttons>
        </ion-toolbar>
        <ion-card style="background: white;">
          <ion-card-content>
          <p>Chat with <i>WAISE-Bot</i>!</p>
          </ion-card-content>
        </ion-card>
    </chat-pane>
  </fab-app>
`;
document.body.innerHTML += htmlContent;
}