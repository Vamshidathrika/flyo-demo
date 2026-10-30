package com.flyo.rescue.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class WhatsAppChatLogService {

    public record ChatMessage(
            String id,
            String timestamp,
            String direction, // "INBOUND" (Operator -> Flyo) or "OUTBOUND" (Flyo -> Operator)
            String sender,
            String recipient,
            String text,
            List<Map<String, String>> buttons
    ) {}

    private final List<ChatMessage> chatHistory = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public void logInbound(String sender, String text) {
        String ts = LocalDateTime.now().format(TIME_FMT);
        chatHistory.add(new ChatMessage(
                UUID.randomUUID().toString(),
                ts,
                "INBOUND",
                sender != null ? sender : "Operator",
                "Flyo Operations",
                text,
                Collections.emptyList()
        ));
    }

    public void logOutbound(String recipient, String text, List<Map<String, String>> buttons) {
        String ts = LocalDateTime.now().format(TIME_FMT);
        chatHistory.add(new ChatMessage(
                UUID.randomUUID().toString(),
                ts,
                "OUTBOUND",
                "Flyo Operations",
                recipient != null ? recipient : "Operator",
                text,
                buttons != null ? new ArrayList<>(buttons) : Collections.emptyList()
        ));
    }

    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }

    public void clear() {
        chatHistory.clear();
    }
}
