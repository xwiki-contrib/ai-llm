// Initialize marked library
marked.use({ breaks: true });

// Initialize variables
let currentRequest = null;
let abortController = null;
let conversationHistory = [];
let userSettings = {
    model: '',
    temperature: 1,
    stream: true,
    settingsCollapsed: false
};
let chatHistory;
let chatInput;
let sendButton;
let stopButton;
let modelSelect;
let temperatureInput;
let streamCheckbox;
let chatWidget;
let toggleChatButton;
let settingsContainer;
let settingsToggle;
let newConvButton;

// Set base URL and wiki name for XWikiAiAPI
XWikiAiAPI.setBaseURL('http://localhost:8082/xwiki');
XWikiAiAPI.setWikiName('xwiki');

// Create the chat widget HTML dynamically
function createChatWidget() {
    const chatWidgetElement = document.createElement('div');
    chatWidgetElement.id = 'chat-widget';
    chatWidgetElement.innerHTML = `
        <div id="chat-container">
            <h2>XWiki AI Chat</h2>
            <div class="settings-container">
                <button id="new-conv">New</button>
                <button id="settings-toggle">Settings</button>
                <div class="settings-wrapper">
                    <div class="settings-top-line"></div>
                    <div class="settings">
                        <div>
                            <label for="model-select">Model:</label>
                            <select id="model-select"></select>
                        </div>
                        <div>
                            <label for="temperature-input">Temp:</label>
                            <input type="number" id="temperature-input" min="0" max="2" step="0.1" value="1">
                        </div>
                        <div>
                            <label for="stream-checkbox">Stream:</label>
                            <input type="checkbox" id="stream-checkbox" checked>
                        </div>
                    </div>
                    <div class="settings-bottom-line"></div>
                </div>
            </div>
            <div id="chat-history"></div>
            <textarea id="chat-input" placeholder="Type your message..."></textarea>
            <div id="button-container">
                <button id="send-button">Send</button>
                <button id="stop-button" style="display: none;">Stop</button>
            </div>
        </div>
    `;
    document.body.appendChild(chatWidgetElement);
    return chatWidgetElement;
}

// Create the toggle chat button HTML dynamically
function createToggleChatButton() {
    const toggleChatButtonElement = document.createElement('button');
    toggleChatButtonElement.id = 'toggle-chat-button';
    toggleChatButtonElement.textContent = 'LLM Chat';
    document.body.appendChild(toggleChatButtonElement);
    return toggleChatButtonElement;
}

// Fetch available models and populate the select dropdown
async function populateModelSelect() {
    try {
        const response = await XWikiAiAPI.getModels();
        const models = response.data;
        modelSelect.innerHTML = '';
        models.forEach(model => {
            const option = document.createElement('option');
            option.value = model.id;
            option.text = model.name;
            modelSelect.appendChild(option);
        });
        // Set the selected model to the user's stored setting or the first option
        const storedModel = userSettings.model;
        if (storedModel && models.some(model => model.id === storedModel)) {
            modelSelect.value = storedModel;
        } else {
            modelSelect.selectedIndex = 0;
            userSettings.model = modelSelect.value;
        }
    } catch (error) {
        console.error('Failed to fetch models:', error);
    }
}

// Initialize the chat widget and toggle button
async function initializeChatWidget() {
    chatWidget = createChatWidget();
    toggleChatButton = createToggleChatButton();

    // Get references to the dynamically created elements
    chatHistory = document.getElementById('chat-history');
    chatInput = document.getElementById('chat-input');
    sendButton = document.getElementById('send-button');
    stopButton = document.getElementById('stop-button');
    modelSelect = document.getElementById('model-select');
    temperatureInput = document.getElementById('temperature-input');
    streamCheckbox = document.getElementById('stream-checkbox');
    settingsContainer = document.querySelector('.settings-container');
    settingsToggle = document.getElementById('settings-toggle');
    newConvButton = document.getElementById('new-conv');

    // Populate the model select dropdown
    await populateModelSelect();

    // Load user settings from local storage
    loadUserSettings();

    // Update user settings when changed
    modelSelect.addEventListener('change', updateUserSettings);
    temperatureInput.addEventListener('input', updateUserSettings);
    streamCheckbox.addEventListener('change', updateUserSettings);

    // Add event listeners
    newConvButton.addEventListener('click', startNewConversation);
    sendButton.addEventListener('click', sendMessage);
    chatInput.addEventListener('keydown', handleChatInputKeydown);
    toggleChatButton.addEventListener('click', toggleChatWidget);
    chatInput.addEventListener('focus', handleChatInputFocus);
    chatInput.addEventListener('blur', handleChatInputBlur);
    settingsToggle.addEventListener('click', toggleSettings);

    // Load last conversation from local storage
    loadLastConversation();
}

// Handle new conversation
function startNewConversation() {
    // Clear the conversation history
    conversationHistory = [];
    saveConversationHistory();

    // Clear the chat history
    chatHistory.innerHTML = '';
}

// Handle chat input keydown event
function handleChatInputKeydown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
}

// Toggle the visibility of the chat widget
function toggleChatWidget() {
    chatWidget.style.display = chatWidget.style.display === 'none' ? 'block' : 'none';
}

// Handle chat input focus event
function handleChatInputFocus() {
    chatWidget.classList.add('keyboard-open');
}

// Handle chat input blur event
function handleChatInputBlur() {
    chatWidget.classList.remove('keyboard-open');
}

// Change the state of the send and stop buttons
function changeBtnState(enabled) {
    sendButton.disabled = !enabled;
    stopButton.style.display = enabled ? 'none' : 'inline-block';
}

// Show the waiting animation
function showWaitingAnimation() {
    const waitingLine = document.createElement('div');
    waitingLine.classList.add('waiting-line');
    waitingLine.innerHTML = `
        <div class="dot dot1"></div>
        <div class="dot dot2"></div>
        <div class="dot dot3"></div>
    `;
    chatHistory.appendChild(waitingLine);
    chatHistory.scrollTop = chatHistory.scrollHeight;
}

// Remove the waiting animation
function removeWaitingAnimation() {
    const waitingLine = chatHistory.querySelector('.waiting-line');
    if (waitingLine) {
        waitingLine.remove();
    }
}

// Send a message to the assistant
function sendMessage() {
    const userMessage = chatInput.value.trim();
    if (userMessage === '') return;

    // Add user message to conversation history
    conversationHistory.push({ role: 'user', content: userMessage });

    // Display user message in the chat history
    displayUserMessage(userMessage);

    // Clear the chat input
    chatInput.value = '';

    // Create a new abort controller for the request
    abortController = new AbortController();
    const signal = abortController.signal;

    // Create a new request with the full conversation history
    const request = new ChatCompletionRequest(
        modelSelect.value,
        parseFloat(temperatureInput.value),
        conversationHistory,
        streamCheckbox.checked
    );

    // Display the assistant message container in the chat history
    const assistantMessageElement = displayAssistantMessage('', modelSelect.options[modelSelect.selectedIndex].text, 0);

    // Show the waiting animation
    showWaitingAnimation();

    // Send the request to the API
    if (request.stream) {
        handleStreamingRequest(request, signal, assistantMessageElement);
    } else {
        handleNonStreamingRequest(request, signal, assistantMessageElement);
    }

    // Disable the send button and show the stop button
    changeBtnState(false);

    // Add event listener to the stop button
    stopButton.addEventListener('click', stopRequest);
}

// Handle streaming request
function handleStreamingRequest(request, signal, assistantMessageElement) {
    let messageText = '';
    const startTime = new Date().getTime();
    let updateTimer;

    updateTimer = setInterval(() => {
        updateResponseTime(startTime, assistantMessageElement.previousElementSibling);
    }, 100);

    XWikiAiAPI.getCompletions(request, messageChunk => {
        if (messageChunk.choices.length > 0 && messageChunk.choices[0].delta.content !== null) {
            messageText += messageChunk.choices[0].delta.content;
            assistantMessageElement.innerHTML = DOMPurify.sanitize(marked.parse(messageText), { FORBID_TAGS: ['style'], FORBID_ATTR: ['src'] });
            removeWaitingAnimation();
        }
    }, signal)
        .then((usageData) => {
            if (messageText !== '') {
                conversationHistory.push({ role: 'assistant', content: assistantMessageElement.textContent });
                saveConversationHistory();
            }
            clearInterval(updateTimer);

            if (usageData) {
                displayUsageInfo(assistantMessageElement.previousElementSibling, usageData, startTime);
            }
        })
        .catch(error => {
            handleRequestError(error, updateTimer);
        })
        .finally(() => {
            changeBtnState(true);
        });
}

// Display usage information
function displayUsageInfo(assistantLabel, usageData, startTime) {
    const responseTime = endTimer(startTime);
    assistantLabel.innerHTML = `<strong>Assistant (${modelSelect.options[modelSelect.selectedIndex].text})</strong><em> - &Delta;T ${responseTime.toFixed(1)}s </em>`;

    const usageSpan = document.createElement('span');
    usageSpan.classList.add('usage');
    usageSpan.innerHTML = '<em> - tokens<span class="usage-info">(&#128202;)</span></em>';
    assistantLabel.appendChild(usageSpan);

    const usageInfo = document.createElement('div');
    usageInfo.classList.add('usage-info-box');
    usageInfo.innerHTML = `
        <p>Prompt tokens: ${usageData.prompt_tokens}</p>
        <p>Completion tokens: ${usageData.completion_tokens}</p>
        <p>Total tokens: ${usageData.total_tokens}</p>
    `;
    usageSpan.appendChild(usageInfo);
}


// Handle non-streaming request
function handleNonStreamingRequest(request, signal, assistantMessageElement) {
    const startTime = new Date().getTime();

    XWikiAiAPI.getCompletions(request, null, signal)
        .then(response => {
            handleNonStreamingResponse(response, startTime, assistantMessageElement);
        })
        .catch(error => {
            handleRequestError(error);
        })
        .finally(() => {
            changeBtnState(true);
        });
}

// Handle non-streaming response
function handleNonStreamingResponse(response, startTime, assistantMessageElement) {
    const endTime = new Date().getTime();
    const responseTime = (endTime - startTime) / 1000;

    const assistantMessage = response.choices[0].message.content;
    assistantMessageElement.innerHTML = DOMPurify.sanitize(marked.parse(assistantMessage), { FORBID_TAGS: ['style'], FORBID_ATTR: ['src'] });
    conversationHistory.push({ role: 'assistant', content: assistantMessage });
    chatHistory.scrollTop = chatHistory.scrollHeight;
    removeWaitingAnimation();
    saveConversationHistory();

    const assistantLabel = assistantMessageElement.previousElementSibling;
    assistantLabel.innerHTML = `<strong>Assistant (${modelSelect.options[modelSelect.selectedIndex].text})</strong> - <em>&Delta;T ${responseTime.toFixed(1)}s </em>`;
    if (response.usage) {
        const usageSpan = document.createElement('span');
        usageSpan.classList.add('usage');
        usageSpan.innerHTML = ' - <em>tokens</em><span class="usage-info">(&#128202;)</span>';
        assistantLabel.appendChild(usageSpan);

        const usageInfo = document.createElement('div');
        usageInfo.classList.add('usage-info-box');
        usageInfo.innerHTML = `
            <p>Prompt tokens: ${response.usage.prompt_tokens}</p>
            <p>Completion tokens: ${response.usage.completion_tokens}</p>
            <p>Total tokens: ${response.usage.total_tokens}</p>
        `;
        usageSpan.appendChild(usageInfo);
    }
    assistantLabel.innerHTML += ':';
}

function endTimer(startTime) {
    const endTime = new Date().getTime();
    const responseTime = (endTime - startTime) / 1000;
    return responseTime;
}

// Handle request error
function handleRequestError(error, updateTimer) {
    if (error.name === 'AbortError') {
        console.log('Request aborted');
    } else {
        console.error('Failed to get chat completions:', error);
        displayErrorMessage('An error occurred. Please try again.');
    }
    removeWaitingAnimation();
    clearInterval(updateTimer);
}

// Stop the current request
function stopRequest() {
    if (abortController) {
        console.log('Aborting request...');
        abortController.abort();
        abortController = null;
        removeWaitingAnimation();
        changeBtnState(true);
    }
}

// Display user message in the chat history
function displayUserMessage(message) {
    const messageElement = document.createElement('div');
    messageElement.classList.add('user-message');

    const userLabel = document.createElement('div');
    userLabel.classList.add('msg-info');
    userLabel.textContent = 'User:';

    const userMessageContent = document.createElement('div');
    userMessageContent.classList.add('message-content');
    userMessageContent.textContent = message;

    messageElement.appendChild(userLabel);
    messageElement.appendChild(userMessageContent);

    chatHistory.appendChild(messageElement);
    chatHistory.scrollTop = chatHistory.scrollHeight;
}

// Display assistant message in the chat history
function displayAssistantMessage(message, modelName = '', responseTime = null) {
    const messageElement = document.createElement('div');
    messageElement.classList.add('assistant');

    const assistantLabel = document.createElement('div');
    assistantLabel.classList.add('msg-info');

    if (modelName && responseTime !== null) {
        assistantLabel.innerHTML = `<strong>Assistant (${modelName})</strong><em> - &Delta;T ${responseTime.toFixed(1)}s:</em>`;
    } else {
        assistantLabel.innerHTML = '<strong>Assistant:</strong>';
    }

    const assistantMessageContent = document.createElement('div');
    assistantMessageContent.classList.add('message-text');
    assistantMessageContent.innerHTML = DOMPurify.sanitize(marked.parse(message), { FORBID_TAGS: ['style'], FORBID_ATTR: ['src'] });

    messageElement.appendChild(assistantLabel);
    messageElement.appendChild(assistantMessageContent);

    chatHistory.appendChild(messageElement);
    chatHistory.scrollTop = chatHistory.scrollHeight;

    return assistantMessageContent;
}



// Update the response time in the assistant label
function updateResponseTime(startTime, assistantLabel) {
    const currentTime = new Date().getTime();
    const elapsedTime = (currentTime - startTime) / 1000;
    assistantLabel.innerHTML = `<strong>Assistant (${modelSelect.options[modelSelect.selectedIndex].text})</strong> - &Delta;T ${elapsedTime.toFixed(1)}s:`;
}

// Display error message in the chat history
function displayErrorMessage(message) {
    const messageElement = document.createElement('div');
    messageElement.classList.add('error-message');
    messageElement.textContent = message;

    chatHistory.appendChild(messageElement);
    chatHistory.scrollTop = chatHistory.scrollHeight;
}

// Update user settings
function updateUserSettings() {
    userSettings.model = modelSelect.value;
    userSettings.temperature = parseFloat(temperatureInput.value);
    userSettings.stream = streamCheckbox.checked;
    saveUserSettings();
}

// Save user settings to local storage
function saveUserSettings() {
    localStorage.setItem('userSettings', JSON.stringify(userSettings));
}

// Load user settings from local storage
function loadUserSettings() {
    const storedSettings = localStorage.getItem('userSettings');
    if (storedSettings) {
        userSettings = JSON.parse(storedSettings);
        modelSelect.value = userSettings.model;
        temperatureInput.value = userSettings.temperature;
        streamCheckbox.checked = userSettings.stream;

        // Apply the collapsed state to the settings section
        const settingsWrapper = document.querySelector('.settings-wrapper');
        if (userSettings.settingsCollapsed) {
            settingsWrapper.classList.add('collapsed');
        } else {
            settingsWrapper.classList.remove('collapsed');
        }
    }
}


// Save conversation history to local storage
function saveConversationHistory() {
    if (conversationHistory.length === 0) {
        localStorage.removeItem('conversationHistory');
    } else {
        localStorage.setItem('conversationHistory', JSON.stringify(conversationHistory));
    }
}

// Load last conversation from local storage
function loadLastConversation() {
    const storedConversation = localStorage.getItem('conversationHistory');
    if (storedConversation) {
        conversationHistory = JSON.parse(storedConversation);
        conversationHistory.forEach(message => {
            if (message.role === 'user') {
                displayUserMessage(message.content);
            } else if (message.role === 'assistant') {
                displayAssistantMessage(message.content);
            }
        });
}
}

// Toggle settings visibility
function toggleSettings() {
    const settingsWrapper = document.querySelector('.settings-wrapper');
    settingsWrapper.classList.toggle('collapsed');
    userSettings.settingsCollapsed = settingsWrapper.classList.contains('collapsed');
    saveUserSettings();
}


// Call the initialization function when the DOM content is loaded
document.addEventListener('DOMContentLoaded', initializeChatWidget);