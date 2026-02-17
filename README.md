Sample Project GEN AI
This documentation assumes you have at least some knowledge /experience developing applications using java/Springboot.
Step 0.1 — Install runtime prerequisites
1.	Install JDK 21 (set JAVA_HOME).
2.	Install Docker Desktop Docker
3.	Install Ollama Ollama (for local LLM without sending data out)
Step 0.2 — Pull a model in Ollama
Open command prompt (Type CMD from windows)
ollama --version
ollama pull llama3.1
ollama pull nomic-embed-text
If you want to confirm it runs:
ollama run llama3.1 "Say hi in one sentence."
________________________________________
Phase 1 — Start the local services with Docker (vector DB + optional observability)
Step 1.1 — Create a folder for your prototype
From CMD type mkdir north-proto
cd north-proto
mkdir infra
Step 1.2 — Create infra/docker-compose.yml
This runs:
•	Qdrant (vector store for RAG)
•	PostgreSQL (optional now; useful later)
•	Redis (optional now; useful later)
•	Jaeger (optional but nice)
Create file: infra/docker-compose.yml
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"
    volumes:
      - qdrant_data:/qdrant/storage

  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
      POSTGRES_DB: northproto
    ports:
      - "5432:5432"
    volumes:
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  jaeger:
    image: jaegertracing/all-in-one:1.57
    ports:
      - "16686:16686"   # UI
      - "4317:4317"     # OTLP gRPC
      - "4318:4318"     # OTLP HTTP

volumes:
  qdrant_data:
  pg_data:
Step 1.3 — Start services
cd infra
docker compose up -d
Check:
•	Qdrant: http://localhost:6333
•	Jaeger UI: http://localhost:16686
________________________________________
Phase 2 — Create the Spring Boot project (chat prototype first)
Step 2.1 — Use Spring Initializr
Create a new Spring Boot project (Gradle or Maven—your choice).
Choose:
•	Java: 21
•	Spring Boot: 3.x
•	Dependencies:
o	Spring Web
o	Spring Boot Actuator
o	Spring AI Ollama (this is key)
o	(Later) Spring AI Qdrant Vector Store
Name it something like: north-proto-app
Open the project in IntelliJ (use it as your main IDE for the whole project).
________________________________________
Phase 3 — Wire Spring AI to Ollama
Step 3.1 — Configure application.yml
In src/main/resources/application.yml:
spring:
  application:
    name: north-proto-app

  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.1
      embedding:
        options:
          model: nomic-embed-text

management:
  endpoints:
    web:
      exposure:
        include: health,info
Step 3.2 — Add a simple Chat endpoint
Create: src/main/java/.../ChatController.java
package com.example.northproto;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .system("You are a helpful assistant. Keep answers concise.")
                .user(request.message())
                .call()
                .content();
    }

    public record ChatRequest(String message) {}
}
Step 3.3 — Run the app
Run from IntelliJ (or terminal):
./gradlew bootRun
Test:
curl -s http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Explain what an autonomous agent is in 2 sentences."}'
✅ At this point you have a local prototype that calls Ollama locally.
________________________________________
Phase 4 — Add RAG with Qdrant (agent talks to “enterprise data”)
Now you’ll:
1.	ingest docs into Qdrant (embeddings)
2.	retrieve relevant chunks for a question
3.	answer using retrieved context + citations
Step 4.1 — Add Qdrant vector-store dependency
In your build file, add Spring AI Qdrant vector store dependency (name depends on your Spring AI version).
Typical pattern (Gradle):
•	Add Spring AI BOM
•	Add Qdrant starter/module
If you share your build.gradle / pom.xml, I’ll paste the exact dependency lines for your project version (so you don’t fight versions).
Step 4.2 — Add Qdrant config
In application.yml add:
spring:
  ai:
    vectorstore:
      qdrant:
        host: localhost
        port: 6333
        collection-name: north_docs
Step 4.3 — Add an ingest endpoint
You want a super simple ingestion path for your prototype:
•	POST text
•	split into chunks
•	embed each chunk
•	store in Qdrant with metadata
Create: IngestController.java (high-level skeleton)
package com.example.northproto;

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
Step 4.4 — Add an “ask with retrieval” endpoint
Create: RagController.java
package com.example.northproto;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RagController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagController(VectorStore vectorStore, ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
    }

    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody RagAsk req) {

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.query(req.question()).withTopK(5)
        );

        String context = results.stream()
                .map(d -> "- " + d.getContent())
                .collect(Collectors.joining("\n"));

        String answer = chatClient.prompt()
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
                .content();

        List<String> citations = results.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
                .distinct()
                .toList();

        return new RagAnswer(answer, citations);
    }

    public record RagAsk(String question) {}
    public record RagAnswer(String answer, List<String> citations) {}
}
Step 4.5 — Test RAG locally
Ingest:
curl -s http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{"source":"handbook.txt","text":"North Proto Handbook: PTO is 15 days per year. Security: Never share secrets. Support: Email helpdesk."}'
Ask:
curl -s http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"How many PTO days do we get?"}'
✅ Now you have a local “agent-ish” app that talks to your local enterprise data via RAG.
________________________________________
Phase 5 — Add a simple “agent tool” (autonomous agent behavior)
A realistic “agent” needs tools (e.g., “create a ticket”).
Step 5.1 — Create a tool service
Create a mock tool:
package com.example.northproto;

import org.springframework.stereotype.Service;

@Service
public class TicketTool {
    public String createTicket(String title, String body) {
        // Later: write to Postgres or call Jira/ServiceNow
        return "TICKET-" + System.currentTimeMillis();
    }
}
Step 5.2 — Add an agent endpoint that decides tool vs answer
Start simple: rule-based router (later you can let the model choose tools).
@PostMapping("/agent")
public String agent(@RequestBody ChatRequest req) {
    String msg = req.message().toLowerCase();
    if (msg.contains("create ticket") || msg.contains("open a ticket")) {
        // parse title/body simply for now
        String id = ticketTool.createTicket("User Request", req.message());
        return "Created ticket: " + id;
    }
    return chat(req);
}
✅ That’s a basic agent loop starter. Next step is replacing rule-based routing with LLM function calling, plus permission checks + audit logs.
________________________________________
What I need from you to make this flawless (no guessing)
Paste your build.gradle (or pom.xml) and tell me:
1.	Gradle or Maven?
2.	Spring Boot version shown in your project
3.	Do you want Spring AI only, or are you open to adding LangChain4j later?
Then I’ll reply with:
•	the exact dependency lines for Spring AI + Qdrant for your version,
•	a working docker-compose + application.yml,
•	and the minimal set of classes wired correctly (so it compiles first try).

Note: after original example I have enhanced the original Spring controllers (END POINTS) and created all in one controller endpoint (“Assist”). Please check out github project.
Checkout RAG architecture from internet as well.

Next I will be working on converting this project into Python and will  continue enhancing it.

