package north_proto_app.controller;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RagController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObservationRegistry observationRegistry;

    public RagController(VectorStore vectorStore,
                         ChatClient.Builder builder,
                         ObservationRegistry observationRegistry) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
        this.observationRegistry = observationRegistry;
    }

    @PostMapping(value = "/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public RagAnswer ask(@RequestBody RagAsk req) {

        // ---- Span: Qdrant retrieval (RAG) ----
        List<Document> results = Observation
                .createNotStarted("rag.retrieve.qdrant", observationRegistry)
                .lowCardinalityKeyValue("vectorstore", "qdrant")
                .lowCardinalityKeyValue("topK", "5")
                .observe(() -> vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(req.question())
                                .topK(5)
                                .build()
                ));

        String context = results.stream()
                .map(d -> "- " + d.getText())
                .collect(Collectors.joining("\n"));

        // ---- Span: LLM call (Ollama) ----
        String answer = Observation
                .createNotStarted("llm.call.ollama", observationRegistry)
                .lowCardinalityKeyValue("provider", "ollama")
                .lowCardinalityKeyValue("model", "llama3.1")
                .observe(() -> chatClient.prompt()
                        .system("""
                            You answer using ONLY the provided context.
                            If the answer isn't in the context, say: "I don't have enough information in the documents."
                            """)
                        .user("""
                            CONTEXT:
                            %s

                            QUESTION:
                            %s
                            """.formatted(context, req.question()))
                        .call()
                        .content());

        List<String> citations = results.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
                .distinct()
                .toList();

        return new RagAnswer(answer, citations);
    }

    public record RagAsk(String question) {}
    public record RagAnswer(String answer, List<String> citations) {}
}
