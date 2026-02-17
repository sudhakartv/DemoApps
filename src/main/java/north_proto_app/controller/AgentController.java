package north_proto_app.controller;


import north_proto_app.services.TicketTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final ChatClient chatClient;
    private final TicketTool ticketTool;

    public AgentController(ChatClient.Builder builder, TicketTool ticketTool) {
        this.chatClient = builder.build();
        this.ticketTool = ticketTool;
    }

    /**
     * Basic chat endpoint (no tools). Good for baseline sanity checks.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE)
    public String chat(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .system("You are a helpful assistant. Keep answers concise.")
                .user(request.message())
                .call()
                .content();
    }

    /**
     * Agent endpoint: decides whether to call a tool (TicketTool) or just chat.
     * For now, tool selection is rule-based (simple + reliable).
     * Later you can replace routing with LLM tool/function calling.
     */
    @PostMapping(value = "/agent", produces = MediaType.TEXT_PLAIN_VALUE)
    public String agent(@RequestBody ChatRequest request) {
        String msg = request.message() == null ? "" : request.message();
        String lower = msg.toLowerCase(Locale.ROOT);

        // ---- TOOL ROUTING (simple heuristic) ----
        if (looksLikeTicketRequest(lower)) {
            // naive parsing: try to extract a title from quotes, else default
            String title = extractQuotedTitle(msg);
            if (title == null || title.isBlank()) {
                title = "User Request";
            }

            // body can be the full message for now
            String body = msg;

            String ticketId = ticketTool.createTicket(title, body);
            return "Created ticket: " + ticketId + "\nTitle: " + title;
        }

        // ---- FALLBACK: normal chat ----
        return chat(request);
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

    public record ChatRequest(String message) {}
}

