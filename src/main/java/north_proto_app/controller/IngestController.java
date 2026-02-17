package north_proto_app.controller;


import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class IngestController {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public IngestController(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest req) {
        List<Document> docs = chunk(req.text(), 800); // naive chunking
        // Add metadata (tenant, source, docId, etc.) for enterprise-style filtering
        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).getMetadata().put("source", req.source());
            docs.get(i).getMetadata().put("chunk", i);
        }

        vectorStore.add(docs); // Spring AI will embed + store (depending on configuration)
        return Map.of("chunksStored", docs.size(), "source", req.source());
    }

    private List<Document> chunk(String text, int maxChars) {
        List<Document> out = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int end = Math.min(idx + maxChars, text.length());
            String part = text.substring(idx, end);
            out.add(new Document(part));
            idx = end;
        }
        return out;
    }

    public record IngestRequest(String source, String text) {}
}

