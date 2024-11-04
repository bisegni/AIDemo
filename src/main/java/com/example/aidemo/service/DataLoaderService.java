package com.example.aidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DataLoaderService {
    @Value("classpath:/data/evaluation-doc-1.pdf")
    private Resource pdfResource;

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;

    public void load() {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(this.pdfResource);
        List<Document> pdfDocument = pdfReader.get();
        var tokenTextSplitter = new TokenTextSplitter();
        var tokens = tokenTextSplitter.split(pdfDocument);
        this.vectorStore.accept(tokens);
    }

    public String loadDocs() {
        try (InputStream inputStream = pdfResource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            List<Document> documents = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> jsonDoc = objectMapper.readValue(line, Map.class);
                String content = (String) jsonDoc.get("body");
                // Split the content into smaller chunks if it exceeds the token limit
                List<String> chunks = splitIntoChunks(content, 1000);
                // Create a Document for each chunk and add it to the list
                for (String chunk : chunks) {
                    Document document = createDocument(jsonDoc, chunk);
                    documents.add(document);
                }
                // Add documents in batches to avoid memory overload
                if (documents.size() >= 100) {
                    vectorStore.add(documents);
                    documents.clear();
                }
            }
            if (!documents.isEmpty()) {
                vectorStore.add(documents);
            }
            return "All documents added successfully!";
        } catch (Exception e) {
            return "An error occurred while adding documents: " + e.getMessage();
        }
    }
    private Document createDocument(Map<String, Object> jsonMap, String content) {
        Map<String, Object> metadata = (Map<String, Object>) jsonMap.get("metadata");
        metadata.putIfAbsent("sourceName", jsonMap.get("sourceName"));
        metadata.putIfAbsent("url", jsonMap.get("url"));
        metadata.putIfAbsent("action", jsonMap.get("action"));
        metadata.putIfAbsent("format", jsonMap.get("format"));
        metadata.putIfAbsent("updated", jsonMap.get("updated"));
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
