package com.example.aidemo.controller;

import java.util.List;

public record OpenAIAnswer(
        String id, // Unique ID for the completion
        String object, // "chat.completion.chunk"
        long created, // Timestamp in seconds
        String model, // Model name (e.g., "gpt-3.5-turbo")
        List<Choice> choices // List of choices (usually just one)
) {
    public record Choice(
            int index, // Index of the choice (usually 0)
            Delta delta, // Delta content for streaming
            String finishReason // Null for intermediate chunks, "stop" for the final chunk
    ) {}

    public record Delta(
            String role, // Role of the message (e.g., "assistant")
            String content // Content of the message
    ) {}
}