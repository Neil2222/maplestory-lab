package net.server.admin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AdminEventLog {
    public record Entry(long timestamp, String type, String message) {}

    private static final int CAPACITY = 200;
    private static final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);

    private AdminEventLog() {}

    public static synchronized void add(String type, String message) {
        if (entries.size() >= CAPACITY) {
            entries.pollFirst();
        }
        entries.addLast(new Entry(System.currentTimeMillis(), type, message));
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }
}
