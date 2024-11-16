package com.example.aidemo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
            
            respond directly to the answer without giving personal opinions or additional information.
            """;
    String llmPrompt = """
            You are an advanced language model assisting with queries for a Spring AI Vector Index. 
            Your task is to construct a simple filter expression for the `eventDate` field based on the user's request.
            
            Context:
            1. The metadata contains a field named `eventDate` that stores dates in the general format `YYYY-MM-DD`.
            2. If the user's request implies filtering by `eventDate`, return the filter expression in the format:
               `<field-name> <operator> "<value>"`
               Examples of valid expressions:
               - eventDate == "2024-11-14"
               - eventDate >= "2024-11-01"
               - eventDate >= "2024-11-14" && eventDate < "2024-11-15"
            3. If the request implies filtering for a specific date without a time, treat the date as the start of the day. 
               For example:
               - If the user requests "What happened on 11/14/2024?" the expression should be:
                 `eventDate >= "2024-11-14" && eventDate < "2024-11-15"`
            4. The output must use the SQL logical operators `&&` (for "AND") and `||` (for "OR").
            5. The values for `eventDate` must be enclosed in double quotes (`"`), and the format must strictly follow `YYYY-MM-DD`.
            6. If no filtering by `eventDate` is required, return the message: 'NO_INDEX'
            
            Tasks:
            1. Analyze the user's request to determine whether filtering by `eventDate` is necessary.
            2. If necessary, construct the exact filter expression in the specified format. 
               - For a specific date, generate an equality or range expression.
               - Use the `YYYY-MM-DD` format for all dates in the expression.
            3. Return only the string representation of the filter expression. Ensure the field name is `eventDate`, 
               and use `&&` and `||` as logical operators. Avoid including any additional text or explanations.
            
            Example Scenarios:
            - User asks: "What happened on 11/14/2024?" → Return:
              eventDate >= "2024-11-14" && eventDate < "2024-11-15"
            - User asks: "Show me events after 11/01/2024." → Return:
              eventDate >= "2024-11-01"
            - User asks: "Retrieve all events." → Return:
              No query on eventDate is necessary.
            
            Output:
            - Return only the exact string representation of the filter expression for `eventDate`, 
              or indicate that no query is needed.
            """;

    public RagController(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .build();
    }

    @GetMapping("/question")
    public String question(@RequestParam(value = "message") String message) {
        if (message == null || message.isBlank()) {
            return "Please provide a message";
        }
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping("/question-prompt")
    public String questionCustom(@RequestParam(value = "message") String message) {
        if (message == null || message.isBlank()) {
            return "Please provide a message";
        }
        // ask to llm if we need to better filter the documents
        Message indexCreationMessage = new SystemPromptTemplate(llmPrompt).createMessage(Map.of("user-request", message));
        var indexAnswer = chatClient.prompt(new Prompt(List.of(indexCreationMessage, new UserMessage(message)))).call();
        var indexRule = indexAnswer.content();

        // Retrieve documents matching the query
        List<Document> allDocuments = null;
        System.out.println("Searching for documents...");
        if( indexRule != null && !indexRule.contains("NO_INDEX")) {
            allDocuments = vectorStore.similaritySearch(
                    SearchRequest.defaults()
                            .withQuery(message)
                            .withFilterExpression(indexRule)
                            .withSimilarityThreshold(0.5)
                            .withTopK(100)
            );
        } else {
            allDocuments = vectorStore.similaritySearch(
                    SearchRequest.defaults()
                            .withQuery(message)
                            .withSimilarityThreshold(0.6)
                            .withTopK(100)
            );
        }

        // If too many documents, chunk them
        int maxDocsPerPrompt = 4; // Define the max number of documents per chunk
        List<List<Document>> documentChunks = chunkDocuments(allDocuments, maxDocsPerPrompt);

        // Create a response for each chunk
        System.out.println("Creating response...");
        StringBuilder finalResponse = new StringBuilder();
        int chunkNumber = 1;
        for (List<Document> chunk : documentChunks) {
            System.out.println("Get answer for chunk..." + chunkNumber++);
            // Create a prompt for each chunk
            var prompt = createPrompt(message, chunk);
            var ccResponse = chatClient.prompt(prompt).call();
            finalResponse.append(ccResponse.content()).append("\n");
        }

        return finalResponse.toString();
    }

    /**
     * Splits a list of documents into chunks of the specified size.
     */
    private List<List<Document>> chunkDocuments(List<Document> documents, int chunkSize) {
        List<List<Document>> chunks = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += chunkSize) {
            chunks.add(documents.subList(i, Math.min(i + chunkSize, documents.size())));
        }
        return chunks;
    }

    private Prompt createPrompt(String message, List<Document> context) {
        String collect = context.stream().map(Document::getContent).collect(Collectors.joining(System.lineSeparator()));
        Message createdMessage = new SystemPromptTemplate(template).createMessage(Map.of("documents", collect));
        UserMessage userMessage = new UserMessage(message);
        return new Prompt(List.of(createdMessage, userMessage));
    }
}
