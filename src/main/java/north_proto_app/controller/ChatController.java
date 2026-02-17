package north_proto_app.controller;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;


    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping("/chatTest")
    public String chat(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .system("You are a helpful assistant. Keep answers concise.")
                .user(request.message())
                .call()
                .content();
    }

    public record ChatRequest(String message) {}
}

