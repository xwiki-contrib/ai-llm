/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm.openai;

import java.util.List;
import java.util.Map;

import org.xwiki.stability.Unstable;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * A request to complete a chat message.
 *
 * @param model the model to use for completion
 * @param messages the messages in the conversation
 * @param temperature the sampling temperature, between 0 and 2, higher values make the output more random
 * @param topP the nucleus sampling parameter, between 0 and 1, an alternative to the temperature where only tokens
 * comprising the top topP probability mass are considered
 * @param n the number of chat completion choices to generate for each input message
 * @param stream whether to stream the completions
 * @param stop up to 4 sequences where the API will stop generating further tokens
 * @param maxTokens the maximum number of tokens to generate
 * @param presencePenalty a number between -2 and 2, positive values penalize new tokens based on whether they appear
 * in the text so far
 * @param frequencyPenalty a number between -2 and 2, positive values penalize new tokens based on their frequency in
 * the text so far
 * @param logitBias the logit bias modifies the likelihood of specified tokens appearing in the completion. The keys
 * are token IDs, the values are between -100 and 100. The bias values are added to the logits that are generated by
 * the model before sampling the token
 * @param streamOptions the options for streaming responses
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonAutoDetect(creatorVisibility = JsonAutoDetect.Visibility.ANY)
public record ChatCompletionRequest(
    String model,
    List<ChatMessage> messages,
    Double temperature,
    Double topP,
    Integer n,
    Boolean stream,
    List<String> stop,
    Integer maxTokens,
    Double presencePenalty,
    Double frequencyPenalty,
    Map<String, Integer> logitBias,
    StreamOptions streamOptions
    // Tools are currently missing
) {
    /**
     * @return a new builder initialized with this request
     */
    public Builder but()
    {
        return new Builder()
            .model(this.model())
            .messages(this.messages())
            .temperature(this.temperature())
            .topP(this.topP())
            .n(this.n())
            .stream(this.stream())
            .stop(this.stop())
            .maxTokens(this.maxTokens())
            .presencePenalty(this.presencePenalty())
            .frequencyPenalty(this.frequencyPenalty())
            .logitBias(this.logitBias())
            .streamOptions(this.streamOptions);

    }

    /**
     * @return a new, empty builder
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * A builder for creating instances of ChatCompletionRequest.
     */
    public static class Builder
    {
        private String model;
        private List<ChatMessage> messages;
        private Double temperature;
        private Double topP;
        private Integer n;
        private Boolean stream;
        private List<String> stop;
        private Integer maxTokens;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Map<String, Integer> logitBias;
        private StreamOptions streamOptions;

        /**
         * @param model the model to use for completion
         * @return the builder with the updated model
         */
        public Builder model(String model)
        {
            this.model = model;
            return this;
        }

        /**
         * @param messages the messages in the conversation
         * @return the builder with the updated messages
         */
        public Builder messages(List<ChatMessage> messages)
        {
            this.messages = messages;
            return this;
        }

        /**
         * @param temperature the sampling temperature, between 0 and 2, higher values make the output more random
         * @return the builder with the updated temperature
         */
        public Builder temperature(Double temperature)
        {
            this.temperature = temperature;
            return this;
        }

        /**
         * @param topP the nucleus sampling parameter, between 0 and 1, an alternative to the temperature where only
         * tokens comprising the top topP probability mass are considered
         * @return the builder with the updated topP
         */
        public Builder topP(Double topP)
        {
            this.topP = topP;
            return this;
        }

        /**
         * @param n the number of chat completion choices to generate for each input message
         * @return the builder with the updated n
         */
        public Builder n(Integer n)
        {
            this.n = n;
            return this;
        }

        /**
         * @param stream true to stream the completions, false otherwise
         * @return the builder with the updated stream setting
         */
        public Builder stream(Boolean stream)
        {
            this.stream = stream;
            return this;
        }

        /**
         * @param stop up to 4 sequences where the API will stop generating further tokens
         * @return the builder with the updated stop sequences
         */
        public Builder stop(List<String> stop)
        {
            this.stop = stop;
            return this;
        }

        /**
         * @param maxTokens the maximum number of tokens to generate
         * @return the builder with the updated maxTokens
         */
        public Builder maxTokens(Integer maxTokens)
        {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * @param presencePenalty a number between -2 and 2, positive values penalize new tokens based on whether they
         * appear in the text so far
         * @return the builder with the updated presence penalty
         */
        public Builder presencePenalty(Double presencePenalty)
        {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * @param frequencyPenalty a number between -2 and 2, positive values penalize new tokens based on their
         * frequency in the text so far
         * @return the builder with the updated frequency penalty
         */
        public Builder frequencyPenalty(Double frequencyPenalty)
        {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * @param logitBias the logit bias modifies the likelihood of specified tokens appearing in the completion. The
         * keys are token IDs, the values are between -100 and 100. The bias values are added to the logits that are
         * generated by the model before sampling the token
         * @return the builder with the updated logit bias
         */
        public Builder logitBias(Map<String, Integer> logitBias)
        {
            this.logitBias = logitBias;
            return this;
        }

        /**
         * @param streamOptions the options for streaming responses
         * @return the builder with the updated stream options
         */
        public Builder streamOptions(StreamOptions streamOptions)
        {
            this.streamOptions = streamOptions;
            return this;
        }

        /**
         * Builds an instance of ChatCompletionRequest with the current settings.
         *
         * @return a new ChatCompletionRequest with the specified settings
         */
        public ChatCompletionRequest build()
        {
            return new ChatCompletionRequest(
                this.model,
                this.messages,
                this.temperature,
                this.topP,
                this.n,
                this.stream,
                this.stop,
                this.maxTokens,
                this.presencePenalty,
                this.frequencyPenalty,
                this.logitBias,
                this.streamOptions
            );
        }
    }
}
