package com.example.aidemo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class RagController {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private String template = """
            You're assisting with questions about the employees working in the company.
            Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
            If unsure, simply state that you don't know.
            DOCUMENTS:
            {documents}
            """;
    public RagController(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .build();
    }
    @GetMapping("/question")
    public String question(@RequestParam(value = "message") String message) {
        if(message == null || message.isBlank()) {
            return "Please provide a message";
        }
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping("/question-prompt")
    public String questionCustom(@RequestParam(value = "message") String message) {
        if(message == null || message.isBlank()) {
            return "Please provide a message";
        }
        var pertinentDocument = vectorStore.similaritySearch(SearchRequest.query(message).withSimilarityThreshold(0.5));
        var prompt= createPrompt(message, pertinentDocument);
        return chatClient.prompt(prompt)
                .call()
                .content();
    }

    private Prompt createPrompt(String message, List<Document> context) {
        String collect = context.stream().map(Document::getContent).collect(Collectors.joining(System.lineSeparator()));
        Message createdMessage = new SystemPromptTemplate(template).createMessage(Map.of("documents", collect));
        UserMessage userMessage = new UserMessage(message);
        return new Prompt(List.of(createdMessage, userMessage));
    }
}
