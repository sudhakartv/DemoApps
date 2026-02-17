package north_proto_app.services;

import org.springframework.stereotype.Service;

@Service
public class TicketTool {
    public String createTicket(String title, String body) {
        // Later: write to Postgres or call Jira/ServiceNow
        return "TICKET-" + System.currentTimeMillis();
    }
}

