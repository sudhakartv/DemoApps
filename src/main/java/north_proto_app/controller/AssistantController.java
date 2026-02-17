package north_proto_app.controller;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import north_proto_app.services.TicketTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final TicketTool ticketTool;
    private final ObservationRegistry observationRegistry;

    public AssistantController(
            VectorStore vectorStore,
            ChatClient.Builder builder,
            TicketTool ticketTool,
            ObservationRegistry observationRegistry
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
        this.ticketTool = ticketTool;
        this.observationRegistry = observationRegistry;
    }

    /**
     * One endpoint that decides:
     *  - tool vs chat
     *  - qdrant(RAG) vs normal chat
     *  - if RAG can't answer: fallback to normal chat (important!)
     */
    @PostMapping(value = "/assist", produces = MediaType.APPLICATION_JSON_VALUE)
    public AssistResponse assist(@RequestBody AssistRequest request) {
        final String msg = request.message() == null ? "" : request.message().trim();
        final String lower = msg.toLowerCase(Locale.ROOT);

        // 1) TOOL ROUTING (TicketTool)
        if (looksLikeTicketRequest(lower)) {
            return Observation
                    .createNotStarted("router.tool.ticket", observationRegistry)
                    .lowCardinalityKeyValue("tool", "TicketTool")
                    .observe(() -> handleTicket(msg));
        }

        // 2) RAG vs CHAT routing
        final boolean attemptRag = shouldAttemptRag(lower);

        if (!attemptRag) {
            // Straight to chat
            final String answer = Observation
                    .createNotStarted("llm.call.ollama", observationRegistry)
                    .lowCardinalityKeyValue("provider", "ollama")
                    .lowCardinalityKeyValue("mode", "chat")
                    .observe(() -> plainChat(msg));

            return new AssistResponse("chat", answer, List.of(), null, null);
        }

        // Attempt RAG
        final RagResult rag = Observation
                .createNotStarted("rag.flow", observationRegistry)
                .lowCardinalityKeyValue("vectorstore", "qdrant")
                .observe(() -> ragAnswerOrNull(msg));

        // Fallback if:
        //  - no docs
        //  - blank answer
        //  - model refused because docs don't contain it
        if (rag == null || rag.answer == null || rag.answer.isBlank() || isRagRefusal(rag.answer)) {

            final String answer = Observation
                    .createNotStarted("llm.call.ollama", observationRegistry)
                    .lowCardinalityKeyValue("provider", "ollama")
                    .lowCardinalityKeyValue("mode", "chat_fallback")
                    .observe(() -> plainChat(msg));

            return new AssistResponse("chat", answer, List.of(), null, null);
        }

        return new AssistResponse("rag", rag.answer, rag.citations, null, null);
    }

    // -----------------------
    // Tool handling
    // -----------------------

    private AssistResponse handleTicket(String msg) {
        final String rawTitle = extractQuotedTitle(msg);
        final String title = (rawTitle == null || rawTitle.isBlank()) ? "User Request" : rawTitle;
        final String body = msg;

        final String ticketId = Observation
                .createNotStarted("tool.call.ticket.create", observationRegistry)
                .lowCardinalityKeyValue("tool", "TicketTool")
                .observe(() -> ticketTool.createTicket(title, body));

        final String answer = "Created ticket: " + ticketId + "\nTitle: " + title;

        return new AssistResponse("tool", answer, List.of(), ticketId, title);
    }

    private boolean looksLikeTicketRequest(String lower) {
        return lower.contains("create ticket")
                || lower.contains("open a ticket")
                || lower.contains("raise a ticket")
                || lower.contains("file a ticket")
                || lower.contains("submit a ticket")
                || lower.contains("log a ticket");
    }

    /**
     * Extracts a title if the user wrote something like:
     * create ticket "VPN not working"
     */
    private String extractQuotedTitle(String msg) {
        int first = msg.indexOf('"');
        if (first < 0) return null;
        int second = msg.indexOf('"', first + 1);
        if (second < 0) return null;
        return msg.substring(first + 1, second).trim();
    }

    // -----------------------
    // Chat / RAG
    // -----------------------

    private String plainChat(String msg) {
        return chatClient.prompt()
                .system("You are a helpful assistant. Keep answers concise.")
                .user(msg)
                .call()
                .content();
    }

    /**
     * RAG attempt:
     *  - retrieve from Qdrant
     *  - if no docs: return null (caller will fallback to chat)
     *  - else: call LLM with context-only instruction
     */
    private RagResult ragAnswerOrNull(String question) {
        // ---- Span: Qdrant retrieval ----
        final List<Document> results = Observation
                .createNotStarted("rag.retrieve.qdrant", observationRegistry)
                .lowCardinalityKeyValue("vectorstore", "qdrant")
                .lowCardinalityKeyValue("topK", "5")
                .observe(() -> vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(5)
                                // If your Spring AI version supports it, this reduces irrelevant hits:
                                // .similarityThreshold(0.75)
                                .build()
                ));

        if (results == null || results.isEmpty()) {
            return null;
        }

        final String context = results.stream()
                .map(d -> "- " + safeText(d.getText()))
                .collect(Collectors.joining("\n"));

        // If context is super tiny, treat as “no useful docs”
        if (context.trim().length() < 40) {
            return null;
        }

        // ---- Span: LLM call (Ollama) ----
        final String answer = Observation
                .createNotStarted("llm.call.ollama", observationRegistry)
                .lowCardinalityKeyValue("provider", "ollama")
                .lowCardinalityKeyValue("model", "llama3.1")
                .lowCardinalityKeyValue("mode", "rag")
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
                            """.formatted(context, question))
                        .call()
                        .content());

        final List<String> citations = results.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        return new RagResult(answer, citations);
    }

    private String safeText(String s) {
        return s == null ? "" : s;
    }

    /**
     * If RAG refuses (because answer isn't in docs), fallback to normal chat.
     * Tune these phrases to match whatever your system prompt uses.
     */
    private boolean isRagRefusal(String answer) {
        if (answer == null) return true;
        final String a = answer.toLowerCase(Locale.ROOT);
        return a.contains("i don't have enough information in the documents")
                || a.contains("not enough information in the documents")
                || a.contains("not in the context")
                || a.contains("only the provided context");
    }

    /**
     * Simple router heuristic:
     * - Skip RAG for short/social messages.
     * - Attempt RAG for "docs/policy/how do I..." style questions or anything longer.
     */
    private boolean shouldAttemptRag(String lower) {
        final String msg = (lower == null) ? "" : lower.trim();
        if (msg.isBlank()) return false;

        // obvious small talk / pleasantries
        if (msg.length() <= 20) {
            if (msg.equals("hi") || msg.equals("hello") || msg.equals("hey")
                    || msg.startsWith("thanks") || msg.startsWith("thank you")
                    || msg.equals("ok") || msg.equals("okay")) {
                return false;
            }
        }

        // explicit “use docs” signals
        if (msg.contains("in the docs") || msg.contains("from the docs") || msg.contains("documentation")
                || msg.contains("handbook") || msg.contains("policy") || msg.contains("procedure")
                || msg.contains("north_docs") || msg.contains("qdrant")) {
            return true;
        }

        // question-like / instructional prompts tend to benefit from RAG
        if (msg.contains("how do i") || msg.contains("how to") || msg.contains("what is")
                || msg.contains("where is") || msg.contains("explain") || msg.contains("?")) {
            return true;
        }

        // longer messages: try RAG first (then fall back if RAG refuses)
        return msg.length() >= 60;
    }

    // -----------------------
    // DTOs
    // -----------------------

    public record AssistRequest(String message) {}

    /**
     * route: "tool" | "rag" | "chat"
     * citations: populated only for route="rag"
     * ticketId/title: populated only for route="tool"
     */
    public record AssistResponse(
            String route,
            String answer,
            List<String> citations,
            String ticketId,
            String ticketTitle
    ) {}

    private static class RagResult {
        final String answer;
        final List<String> citations;

        RagResult(String answer, List<String> citations) {
            this.answer = answer;
            this.citations = (citations == null) ? List.of() : citations;
        }
    }
}
