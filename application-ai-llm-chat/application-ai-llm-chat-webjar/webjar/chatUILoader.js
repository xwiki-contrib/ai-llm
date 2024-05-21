// Load Chat UI
window.onload = () => {
    insertChatUI();
    const fab = document.getElementById('fab');
    const pane = document.getElementById('pane');
    
    // Retrieve the base URL from the script tag
    const scriptTag = document.getElementById('chat-loader');
    const baseURL = scriptTag.getAttribute('data-base-url');
    
    // Set the base URL in XWikiAiAPI
    XWikiAiAPI.setBaseURL(baseURL);

    XWikiAiAPI.setChatUISettings(["temperature","model","stream"]); //"server-address","temperature","model","stream"
  
    // Chat pane buttons handlers
    document.getElementById('chat-close-btn').addEventListener('click', () => fab.close());
  
    const settingsButton = document.getElementById('chat-settings-btn');
    settingsButton.addEventListener('click', () => {
        const event = new CustomEvent('toggleSettingsView', { bubbles: true, composed: true });
        settingsButton.dispatchEvent(event);
    });
  
    // Setup completion request
    let completionRequest = new ChatCompletionRequest(
        "AI.Models.mixtral", // model
        0.5, // temperature
        [], // messages
        true, // streaming
    );
  
    setTimeout(async () => {
        const chatSettings = document.getElementById('chat-settings');
        if (chatSettings && typeof chatSettings.getSettings === "function") {
            try {
                const settings = await chatSettings.getSettings();
                if (!settings.llmServerAddress) {
                    settings.llmServerAddress = XWikiAiAPI.getBaseURL();
                }
                XWikiAiAPI.setBaseURL(settings.llmServerAddress);
                completionRequest.setModel(settings.selectedModel || "AI.Models.mixtral");
                completionRequest.setTemperature(settings.temperature || 1);
                completionRequest.setStream(settings.stream || false);
            } catch (error) {
                console.error("Failed to load settings from chatSettings:", error);
            }
        } else {
            console.error("chatSettings.getSettings is not available");
        }
    }, 500);
  
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
            let messageText = '';
            XWikiAiAPI.getCompletions(completionRequest, async (messageChunk) => {
                const messageTextContainer = incomingMessage.querySelector('.message-text');
                // Check if we have any choices
                if (messageChunk.choices.length > 0) {
                    messageText += messageChunk.choices[0].delta.content;
                    messageTextContainer.innerHTML = marked.parse(messageText);
                }
            })
            .then(() => {
                completionRequest.addMessage("assistant", incomingMessage.querySelector('.message-text').textContent);
                return completionRequest;
            })
            .catch(err => {
                console.error(err);
            });
        } else {
            return XWikiAiAPI.getCompletions(completionRequest).then(async messageChunk => {
                removeLoadingAnimation(); // Clear loading before showing the message
                incomingMessage.querySelector('.message-text').innerHTML = marked.parse(messageChunk.choices[0].message.content);
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
};

function insertChatUI() {
    const htmlContent = `
        <fab-app id="fab">
            <chat-pane id="pane">
                <ion-toolbar slot="header" color="primary">
                <ion-title>XWiki AI Chat</ion-title>
                <ion-buttons slot="primary">
                    <!-- settings button -->
                    <ion-button id="chat-settings-btn">
                        <ion-icon slot="icon-only" name="settings" />
                    </ion-button>
                    <!-- close button -->
                    <ion-button id="chat-close-btn">
                        <ion-icon slot="icon-only" name="close" />
                    </ion-button>
                </ion-buttons>
                </ion-toolbar>
                <chat-settings id="chat-settings" hidden></chat-settings>
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
