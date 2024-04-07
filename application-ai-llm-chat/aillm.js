/**
 * A singleton object that provides methods to interact with the XWiki AI Chat API.
 */
const XWikiAiAPI = (() => {
    let baseURL = 'http://localhost:8080/xwiki'; // Default base URL
    let wikiName = 'xwiki'; // Default wiki name
    let apiKey = ''; // API key for authentication
    let temperature = 1;
    let stream = false;
    let chatUISettings = [];

    /**
     * Generates the fetch options for a request.
     * 
     * @param {string} method - The HTTP method to use.
     * @param {Object|null} body - The body of the request, if any.
     * @return {Object} The fetch options.
     */
    const fetchOptions = (method, body = null) => ({
        method,
        headers: {
            'Content-Type': 'application/json',
            ...(apiKey && { 'Authorization': `Bearer ${apiKey}` })
        },
        ...(body && { body: JSON.stringify(body) })
    });

    /**
     * Handles a streamed response by accumulating the content values and calling the onMessageChunk callback.
     * 
     * @param {Response} response - The response object.
     * @param {Function} onMessageChunk - The callback to call for each message chunk.
     * @return {Promise} A promise that resolves with the accumulated content values.
     */
    const handleStreamedResponse = async (response, onMessageChunk) => {
        let accumulatedChunks = '';
        const reader = response.body.getReader();
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            const chunkText = new TextDecoder().decode(value);
            accumulatedChunks += chunkText;

            // Process accumulated chunks
            let lastIndexOfData = accumulatedChunks.lastIndexOf('data: ');
            if (lastIndexOfData !== -1) {
                let completeData = accumulatedChunks.substring(0, lastIndexOfData);
                accumulatedChunks = accumulatedChunks.substring(lastIndexOfData);

                let jsonMessages = completeData.split('data: ').filter(Boolean).map(msg => 'data: ' + msg);
                jsonMessages.forEach(msg => {
                    // Optionally, process each JSON message as it arrives
                    if (onMessageChunk) onMessageChunk(JSON.parse(msg.replace(/^data: /, '').trim()));
                });
            }
        }

        // Handle the final chunk
        if (accumulatedChunks) {
            if (onMessageChunk) onMessageChunk(JSON.parse(accumulatedChunks.replace(/^data: /, '').trim()));
        }
    };


    /**
     * Encodes the wiki name for use in URLs.
     * 
     * @return {string} The encoded wiki name.
     */
    const encodeWikiName = () => encodeURIComponent(wikiName);

    return {
        /**
         * Sets the base URL of the API.
         * 
         * @param {string} url - The base URL.
         */
        setBaseURL: (url) => {
            baseURL = url;
        },

        /**
         * Sets the API key for authentication.
         * 
         * @param {string} key - The API key.
         */
        setApiKey: (key) => {
            apiKey = key;
        },

        /**
         * Sets the wiki name.
         * 
         * @param {string} name - The name of the wiki.
         */
        setWikiName: (name) => {
            wikiName = name;
        },

        /**
         * Fetches the list of available models.
         * 
         * @return {Promise} A promise that resolves with the list of models.
         */
        getModels: async () => {
            try {
                const response = await fetch(`${baseURL}/rest/wikis/${encodeWikiName()}/aiLLM/v1/models?media=json`, fetchOptions('GET'));
                if (!response.ok) throw new Error('Network response was not ok');
                return await response.json();
            } catch (error) {
                console.error('Failed to fetch models:', error);
                throw error;
            }
        },

        /**
         * Fetches the list of available prompts.
         * 
         * @return {Promise} A promise that resolves with the list of prompts.
         */
        getPrompts: async () => {
            try {
                const response = await fetch(`${baseURL}/rest/wikis/${encodeWikiName()}/aiLLM/v1/prompts?media=json`, fetchOptions('GET'));
                if (!response.ok) throw new Error('Network response was not ok');
                return await response.json();
            } catch (error) {
                console.error('Failed to fetch prompts:', error);
                throw error;
            }
        },

      /**
       * Sends a ChatCompletionRequest to get chat completions with streaming support.
       * 
       * @param {ChatCompletionRequest} request - The completion request.
       * @param {Function} onMessageChunk - The callback to call for each message chunk.
       * @return {Promise} A promise that resolves when the stream is fully processed.
       */
        getCompletions: async (request, onMessageChunk) => {
            if (!(request instanceof ChatCompletionRequest)) {
                throw new Error("The request must be an instance of ChatCompletionRequest");
            }
            try {
                const response = await fetch(`${baseURL}/rest/wikis/${encodeWikiName()}/aiLLM/v1/chat/completions?media=json`, fetchOptions('POST', request.toJSON()));
                if (!response.ok) {
                    throw new Error(`Network response was not ok: ${response.statusText}`);
                }
                if (request.stream) {
                    // Handle streaming response
                    return await handleStreamedResponse(response, onMessageChunk);
                } else {
                    // Non-streaming response
                    return await response.json();
                }
            } catch (error) {
                console.error('Failed to get chat completions:', error);
                throw error;
            }
        },

        /**
         * Set the available settings as an array of strings.
         * 
         * @param {Array} settings - The available settings as an array of strings. Available settings are: "server-address","temperature","model" and "stream"
         */
        setChatUISettings: (settings) => {
            chatUISettings = settings;
        },

        /**
         * Get the available settings as an array of strings.
         * 
         * @return {Array} The available settings.
         * */
        getChatUISettings: () => {
            return chatUISettings;
        }
    };
})();

/**
 * Represents a request for chat completions from the AI model.
 */
class ChatCompletionRequest {
    /**
     * Constructs a new ChatCompletionRequest instance.
     * 
     * @param {string} model - The model to use for generating chat completions.
     * @param {number} temperature - The randomness of the generated completions, usually between 0 and 2.
     * @param {Array} messages - An array of message objects, each containing a role and content.
     */
    constructor(model, temperature, messages, stream) {
        this.setModel(model);
        this.setTemperature(temperature);
        this.setMessages(messages);
        this.setStream(stream)
    }

    /**
     * Sets the model name after validation.
     * 
     * @param {string} model - The model to use for chat completions.
     */
    setModel(model) {
        if (typeof model !== 'string' || model.trim() === '') {
            throw new Error('Model must be a non-empty string.');
        }
        this.model = model;
    }

    /**
     * Sets the temperature after validation.
     * 
     * @param {number} temperature - The temperature for chat completions.
     */
    setTemperature(temperature) {
        if (typeof temperature !== 'number' || temperature < 0 || temperature > 2) {
            throw new Error('Temperature must be a number between 0 and 2.');
        }
        this.temperature = temperature;
    }

    /**
     * Sets the messages array after validation.
     * 
     * @param {Array} messages - The messages for chat completions.
     */
    setMessages(messages) {
        if (!Array.isArray(messages) || messages.some(msg => typeof msg !== 'object' || typeof msg.role !== 'string' || typeof msg.content !== 'string')) {
            throw new Error('Messages must be an array of objects with "role" and "content" strings.');
        }
        this.messages = messages;
    }

    setStream(stream) {
        if (typeof stream !== 'boolean') {
          throw new Error('Stream must be a boolean value.');
        }
        this.stream = stream;
    }

    /**
     * Adds a message to the messages array after validation.
     * 
     * @param {string} role - The role of the message (system, user, or assistant).
     * @param {string} content - The content of the message.
     */
    addMessage(role, content) {
        if (!['system', 'user', 'assistant'].includes(role)) {
            throw new Error('Role must be either "system", "user" or "assistant".');
        }
        if (typeof content !== 'string' || content.trim() === '') {
            throw new Error('Content must be a non-empty string.');
        }
        this.messages.push({ role, content });
    }

    /**
     * Validates the current state of the instance.
     */
    validate() {
        this.setModel(this.model);
        this.setTemperature(this.temperature);
        this.setMessages(this.messages);
        this.setStream(this.stream);
    }

    /**
     * Returns a JSON representation of the instance.
     * 
     * @return {Object} The JSON representation.
     */
    toJSON() {
        this.validate(); // Ensure the object is valid before serialization
        return {
            model: this.model,
            temperature: this.temperature,
            messages: this.messages,
            stream: this.stream
        };
    }
}
window.XWikiAiAPI = XWikiAiAPI;