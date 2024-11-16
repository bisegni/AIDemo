package com.example.aidemo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.james.mime4j.dom.datetime.DateTime;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DataLoaderService {
    @Value("classpath:/data")
    private Resource pdfResource;
    private DateTimeFormatter formatter =  DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;

    public void load(int maxDocuments) {
        try {
            Path folderPath = Paths.get(pdfResource.getURI());
            var allDocuments = Files.list(folderPath)
                    .filter(Files::isRegularFile)
                    .toList();
            // scan all documents
            for (Path path : allDocuments) {
                List<Document> toStore = new ArrayList<>();
                String resourcePath = "data/" + path.getFileName().toString();
                if (resourcePath.endsWith(".pdf")) {
                    ClassPathResource res = new ClassPathResource(resourcePath);
                    // create the resource from path
                    var pagePdfDocumentReader = new PagePdfDocumentReader(res);
                    var extractedDocument = pagePdfDocumentReader.read()
                            .stream()
                            .filter(doc -> {
                                        FilterExpressionBuilder b = new FilterExpressionBuilder();
                                        // check if document is already present in vector store
                                        var foundDoc = vectorStore.similaritySearch(SearchRequest.defaults()
                                                .withQuery(doc.getContent())
                                                .withFilterExpression(b.eq("file_name", path.getFileName().toString()).build()));
                                        return foundDoc.isEmpty();
                                    }
                            )
                            .toList();
                    if (!extractedDocument.isEmpty()) {
                        toStore.addAll(extractedDocument);
                    }
                }
                if (resourcePath.endsWith(".json")) {
                    ClassPathResource res = new ClassPathResource(resourcePath);
                    // create the resource from path
                    loadJson(res.getInputStream(), maxDocuments);
                } else {
                    ClassPathResource res = new ClassPathResource(resourcePath);
                    TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(res);
                    var extractedDocument = tikaDocumentReader.read()
                            .stream()
                            .filter(doc -> {
                                        FilterExpressionBuilder b = new FilterExpressionBuilder();
                                        // check if document is already present in vector store
                                        var foundDoc = vectorStore.similaritySearch(SearchRequest.defaults()
                                                .withQuery(doc.getContent())
                                                .withFilterExpression(b.eq("file_name", path.getFileName().toString()).build()));
                                        return foundDoc.isEmpty();
                                    }
                            )
                            .toList();
                    toStore.addAll(extractedDocument);
                }
                var tokenTextSplitter = new TokenTextSplitter();
                var tokens = tokenTextSplitter.split(toStore);
                this.vectorStore.accept(tokens);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadJson(InputStream inputStream, int maxDocuments) {
        int curDocuments = 0;
        try {
            int docProcessingBatchSize = Math.min(100, maxDocuments);
            // Parse the entire InputStream into an Object
            Object jsonElement = objectMapper.readValue(inputStream, Object.class);
            List<Document> documents = new ArrayList<>();
            if (jsonElement instanceof List) {
                for (Map<String, Object> jsonDoc : (List<Map<String, Object>>) jsonElement) {
                    processMongoDBJsonDocument(jsonDoc, documents);
                    if (documents.size() >= docProcessingBatchSize) {
                        curDocuments += documents.size();
                        System.out.println("Adding 100 documents to the vector store");
                        vectorStore.add(documents);
                        documents.clear();
                        if(curDocuments >= maxDocuments) {
                            break;
                        }
                    }
                }
            } else if (jsonElement instanceof Map) {
                // If it's a single JSON object
                Map<String, Object> jsonDoc = (Map<String, Object>) jsonElement;
                processMongoDBJsonDocument(jsonDoc, documents);
            }

            // Add remaining documents to the vector store
            if (!documents.isEmpty()) {
                vectorStore.add(documents);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void processMongoDBJsonDocument(Map<String, Object> jsonDoc, List<Document> documents) throws JsonProcessingException, ParseException {
        Map<String, Object> mongoDbId = (Map<String, Object>) jsonDoc.get("_id");
        Map<String, Object> eventAtField = (Map<String, Object>) jsonDoc.get("eventAt");
        String eventAtDate = eventAtField.get("$date").toString();
        // create a separate has table with title and text fields
        Map<String, String> titleAndText = new HashMap<>();
        titleAndText.putIfAbsent("title", (String) jsonDoc.getOrDefault("title", ""));
        titleAndText.putIfAbsent("content", (String) jsonDoc.getOrDefault("text", ""));
        titleAndText.putIfAbsent("eventDate", eventAtDate);
        // write map to jsons string
        int currentChunkId = 0;
        List<String> chunks = splitIntoChunks(new ObjectMapper().writeValueAsString(titleAndText), 1000);
        for (String chunk : chunks) {
            currentChunkId++;

            Document document = createDocument(mongoDbId.get("$oid").toString(), eventAtDate, chunk, currentChunkId);
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            // check if document is already present in vector store
            var foundDoc = vectorStore.similaritySearch(SearchRequest.defaults()
                    .withQuery(document.getContent())
                    .withFilterExpression(
                            b.and(
                                    b.eq("mongoDbId", mongoDbId.get("$oid")),
                                    b.eq("chunkId", Integer.toString(currentChunkId))
                            ).build())
            );
            if (foundDoc.isEmpty()) {
                documents.add(document);
            }

        }
    }

    private Document createDocument(String mongoDbId,  String eventAtDate, String content, int chunkId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.putIfAbsent("mongoDbId", mongoDbId);
        metadata.putIfAbsent("eventDate", eventAtDate);
        metadata.putIfAbsent("chunkId", Integer.toString(chunkId));
        return new Document(content, metadata);
    }

    private List<String> splitIntoChunks(String content, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        String[] words = content.split("\\s+");
        StringBuilder chunk = new StringBuilder();
        int tokenCount = 0;
        for (String word : words) {
            // Estimate token count for the word (approximated by character length for simplicity)
            int wordTokens = word.length() / 4;  // Rough estimate: 1 token = ~4 characters
            if (tokenCount + wordTokens > maxTokens) {
                chunks.add(chunk.toString());
                chunk.setLength(0); // Clear the buffer
                tokenCount = 0;
            }
            chunk.append(word).append(" ");
            tokenCount += wordTokens;
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }
}
