package com.example.aidemo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.ResourceUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DataLoaderService {
    @Value("classpath:/data")
    private Resource pdfResource;

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;

    public void load() {
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
                    if(!extractedDocument.isEmpty()){toStore.addAll(extractedDocument);}
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
