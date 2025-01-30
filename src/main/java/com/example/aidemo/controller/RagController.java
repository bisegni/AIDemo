package com.example.aidemo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

@RestController
public class RagController {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final String titleTagsPrompt = """
            You are to generate a JSON document based on the following user text.
            Your task is to create a JSON document with the following structure:
            {{
                "title": "<Generated Title>",
                "tags": ["<tag1>", "<tag2>", ...]
            }}
            
            - The "title" should be a concise and informative title summarizing the main topic of the user text.
            - You are to choose from among the following tags, using the descriptions to determine which tags are the most relevant:
            {{
              "NONE": "for normal logbook entries",
              "FATAL": "for failures that prevent from operation of the machine for more than one hour",
              "ERROR": "for errors in a device/subsystem/program etc.",
              "FIXED": "error that has been fixed",
              "WARN": "for warnings to other users or operators (don't forget, check this...)",
              "INFO": "for hints to other users or operators",
              "MEASURE": "message about a measurement (beam parameter, radiation ..)",
              "IDEA": "extra ideas and communication (\"would be nice to have\" ..)",
              "DOCU": "documentation of some subsystem",
              "TODO": "work to be done",
              "DONE": "work that has been done",
              "DELETE": "marks an entry as deleted: the item is then no more visible in the eLogBook"
            }}
            
            Please output *only* the JSON document and nothing else.
            """;
    private String template = """
            You're assisting to respond to user query summarizing information from the provided from the DOCUMENTS section to provide 
            accurate answers but act as if you knew this information innately.
            DOCUMENTS:
            {documents}
            
            respond directly to the answer without giving personal opinions or additional information, skip irrelevant information and don't specify
            that for some entry in  DOCUMENTS there wasn't no answer. Please answer directly tyo the question leaving out stuff that can be similar
            but arent the same.
            """;
    String filterAndCleanPrompt = """
            Create a summary going directly to the summarization and  do not include any introductory phrases like “Here is the summary” or similar.
            ensure all important details are captured in a seamless flow
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
            Construct a simple filter expression for the eventDate field based on user requests.
            Context:
            	1.	Metadata:
            	•	eventDate is in YYYY-MM-DD format.
            	2.	Rules:
            	•	If filtering by eventDate, return expressions like:
            	•	eventDate == "YYYY-MM-DD"
            	•	eventDate >= "YYYY-MM-DD" && eventDate < "YYYY-MM-DD"
            	•	For a specific date without time, use a range:
            	•	User asks: “What happened on 11/14/2024?” →
            eventDate >= "2024-11-14" && eventDate < "2024-11-15"
            	•	Use SQL-style operators && and ||, and enclose dates in double quotes.
            	•	If no filtering is needed, return: NO_INDEX.
            
            Tasks:
            	1.	Analyze Request:
            	•	Determine if eventDate filtering is required.
            	2.	Construct Filter:
            	•	Use specified formats and operators.
            	3.	Return Result:
            	•	Exact filter string or NO_INDEX.
            
            Examples:
            	•	User: “What happened on 11/14/2024?” →
            eventDate >= "2024-11-14" && eventDate < "2024-11-15"
            	•	User: “Show events after 11/01/2024.” →
            eventDate >= "2024-11-01"
            	•	User: “Retrieve all events.” →
            NO_INDEX
            return only the index found or NO_INDEX, without additional information or personal opinions.
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


    @GetMapping("/title-tags")
    public TitleAndTagsDTO summarize(@RequestParam(value = "message") String message) throws JsonProcessingException {
        Message indexCreationMessage = new SystemPromptTemplate(titleTagsPrompt).createMessage();
        var indexAnswer = chatClient.prompt(new Prompt(List.of(indexCreationMessage, new UserMessage(message)))).call();
        var jsonAnswer = indexAnswer.content();
        return new ObjectMapper().readValue(jsonAnswer, TitleAndTagsDTO.class);
    }

    @GetMapping("/question-prompt")
    public AnswerDTO questionCustom(@RequestParam(value = "message") String message) {
        // summarize the response
        List<RelatedDocumentDTO> relatedDocumentDTOs = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return new AnswerDTO(true, "Invalid message", LocalDateTime.now(), emptyList());
        }
        // ask to llm if we need to better filter the documents
        Message indexCreationMessage = new SystemPromptTemplate(llmPrompt).createMessage(Map.of("user-request", message));
        var indexAnswer = chatClient.prompt(new Prompt(List.of(indexCreationMessage, new UserMessage(message)))).call();
        var indexRule = indexAnswer.content();

        // Retrieve documents matching the query
        List<Document> allDocuments = null;
        System.out.println("Searching for documents...");
        if (indexRule != null && !indexRule.contains("NO_INDEX")) {
            allDocuments = vectorStore.similaritySearch(
                    SearchRequest.query(message)
                            .withFilterExpression(indexRule)
                            .withSimilarityThreshold(0.5)
                            .withTopK(1000)
            );
        } else {
            allDocuments = vectorStore.similaritySearch(
                    SearchRequest.query(message)
                            .withSimilarityThreshold(0.6)
                            .withTopK(1000)
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

            // summarize the response
            var partialContent = ccResponse.content();
            finalResponse.append(partialContent).append("\n");
        }
        var summarizedBatchAnswer = chatClient.prompt(new Prompt(List.of(new SystemPromptTemplate(filterAndCleanPrompt).createMessage(), new UserMessage(finalResponse.toString())))).call();
        var filteredContent = summarizedBatchAnswer.content();
        return new AnswerDTO(
                true,
                filteredContent,
                LocalDateTime.now(),
                relatedDocumentDTOs);
    }

    @GetMapping(value = "/streamed", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AnswerDTO> streamedQuestion(@RequestParam(value = "message") String message) {
        if (message == null || message.isBlank()) {
            return Flux.just(new AnswerDTO(true, "Invalid message", LocalDateTime.now(), Collections.emptyList()));
        }

        return Mono.fromCallable(() -> {
                    // Determine index rule
                    Message indexCreationMessage = new SystemPromptTemplate(llmPrompt).createMessage(Map.of("user-request", message));
                    var indexAnswer = chatClient.prompt(new Prompt(List.of(indexCreationMessage, new UserMessage(message)))).call();
                    return indexAnswer.content();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(indexRule -> {
                    // Retrieve matching documents asynchronously
                    Mono<List<Document>> docsMono = Mono.fromCallable(() -> {
                        if (indexRule != null && !indexRule.contains("NO_INDEX")) {
                            return vectorStore.similaritySearch(
                                    SearchRequest.defaults()
                                            .withQuery(message)
                                            .withFilterExpression(indexRule)
                                            .withSimilarityThreshold(0.5)
                                            .withTopK(1000));
                        } else {
                            return vectorStore.similaritySearch(
                                    SearchRequest.defaults()
                                            .withQuery(message)
                                            .withSimilarityThreshold(0.6)
                                            .withTopK(1000));
                        }
                    }).subscribeOn(Schedulers.boundedElastic());
                    return docsMono.flatMapMany(allDocuments -> {
                        List<List<Document>> documentChunks = chunkDocuments(allDocuments, 4);

                        Flux<AnswerDTO> chunksFlux = Flux.fromIterable(documentChunks)
                                .concatMap(chunk -> Mono.fromCallable(() -> {
                                    // Process each chunk
                                    var prompt = createPrompt(message, chunk);
                                    var ccResponse = chatClient.prompt(prompt).call();

                                    // Optionally summarize/clean the chunk response
                                    var summarizedChunkAnswer = chatClient.prompt(new Prompt(List.of(
                                            new SystemPromptTemplate(filterAndCleanPrompt).createMessage(),
                                            new UserMessage(ccResponse.content())))
                                    ).call();

                                    return new AnswerDTO(false, summarizedChunkAnswer.content(), LocalDateTime.now(), Collections.emptyList());
                                }).subscribeOn(Schedulers.boundedElastic()));

                        // After all chunks have been processed, emit a final message
                        Flux<AnswerDTO> finalMessage = Mono.fromCallable(() ->
                                new AnswerDTO(true, "", LocalDateTime.now(), Collections.emptyList())
                        ).flux().subscribeOn(Schedulers.boundedElastic());

                        return chunksFlux.concatWith(finalMessage);
                    });
                });
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Flux<AnswerDTO> chat(
            @RequestBody @Valid ChatDto chatDto
    ) {
        if (chatDto.message() == null || chatDto.message().isBlank()) {
            return Flux.just(new AnswerDTO(true, "Invalid message", LocalDateTime.now(), Collections.emptyList()));
        }

        return Mono.fromCallable(() -> {
                    // Determine index rule
                    Message indexCreationMessage = new SystemPromptTemplate(llmPrompt).createMessage(Map.of("user-request", chatDto.message()));
                    var indexAnswer = chatClient.prompt(new Prompt(List.of(indexCreationMessage, new UserMessage(chatDto.message())))).call();
                    return indexAnswer.content();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(indexRule -> {
                    // Retrieve matching documents asynchronously
                    Mono<List<Document>> docsMono = Mono.fromCallable(() -> {
                        if (indexRule != null && !indexRule.contains("NO_INDEX")) {
                            return vectorStore.similaritySearch(
                                    SearchRequest.defaults()
                                            .withQuery(chatDto.message())
                                            .withFilterExpression(indexRule)
                                            .withSimilarityThreshold(0.5)
                                            .withTopK(1000));
                        } else {
                            return vectorStore.similaritySearch(
                                    SearchRequest.defaults()
                                            .withQuery(chatDto.message())
                                            .withSimilarityThreshold(0.6)
                                            .withTopK(1000));
                        }
                    }).subscribeOn(Schedulers.boundedElastic());
                    return docsMono.flatMapMany(allDocuments -> {
                        List<List<Document>> documentChunks = chunkDocuments(allDocuments, 4);

                        Flux<AnswerDTO> chunksFlux = Flux.fromIterable(documentChunks)
                                .concatMap(chunk -> Mono.fromCallable(() -> {
                                    // Process each chunk
                                    var prompt = createPrompt(chatDto.message(), chunk);
                                    var ccResponse = chatClient.prompt(prompt).call();

                                    // Optionally summarize/clean the chunk response
                                    var summarizedChunkAnswer = chatClient.prompt(new Prompt(List.of(
                                            new SystemPromptTemplate(filterAndCleanPrompt).createMessage(),
                                            new UserMessage(ccResponse.content())))
                                    ).call();

                                    return new AnswerDTO(false, summarizedChunkAnswer.content(), LocalDateTime.now(), Collections.emptyList());
                                }).subscribeOn(Schedulers.boundedElastic()));

                        // After all chunks have been processed, emit a final message
                        Flux<AnswerDTO> finalMessage = Mono.fromCallable(() ->
                                new AnswerDTO(true, "", LocalDateTime.now(), Collections.emptyList())
                        ).flux().subscribeOn(Schedulers.boundedElastic());

                        return chunksFlux.concatWith(finalMessage);
                    });
                });
    }

    @PostMapping(value = "/v1/chat/completions", produces = "text/event-stream")
    public SseEmitter streamChatCompletion(@RequestBody ChatDto request) {
        SseEmitter emitter = new SseEmitter();

        // Simulate streaming by sending multiple chunks of data
        new Thread(() -> {
            try {
                String message = request.message();
                for (int i = 0; i < 5; i++) {
                    AnswerDTO answer = new AnswerDTO(i==4, "content %d".formatted(i), LocalDateTime.now(), Collections.emptyList());

                    // Send the response as an SSE event
                    emitter.send(SseEmitter.event().data(answer));

                    // Simulate delay
                    Thread.sleep(1000);
                }

                // Complete the stream
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @PostMapping(value = "/v1/chat/completions/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OpenAIAnswer> streamChatCompletionFlux(@RequestBody ChatDto request) {
        String message = request.message();

        // Simulate streaming by emitting multiple responses with a delay
        return Flux.interval(Duration.ofSeconds(1)) // Emit an item every second
                .take(4) // Emit 4 intermediate chunks
                .map(sequence -> {
                    // Intermediate chunk
                    OpenAIAnswer.Delta delta = new OpenAIAnswer.Delta("assistant", "content " + (sequence + 1));
                    OpenAIAnswer.Choice choice = new OpenAIAnswer.Choice(0, delta, null); // finishReason is null
                    return new OpenAIAnswer(
                            "chatcmpl-123", // Unique ID
                            "chat.completion.chunk", // Object type
                            Instant.now().getEpochSecond(), // Timestamp
                            "gpt-3.5-turbo", // Model name
                            Collections.singletonList(choice) // List of choices
                    );
                })
                .concatWithValues(
                        // Final chunk
                        new OpenAIAnswer(
                                "chatcmpl-123",
                                "chat.completion.chunk",
                                Instant.now().getEpochSecond(),
                                "gpt-3.5-turbo",
                                Collections.singletonList(
                                        new OpenAIAnswer.Choice(
                                                0,
                                                new OpenAIAnswer.Delta("assistant", "Final content"),
                                                "stop" // finishReason is "stop"
                                        )
                                )
                        )
                )
                .doOnComplete(() -> {
                    // Optional: Perform any side-effect when the stream completes
                    System.out.println("Stream completed!");
                });
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
