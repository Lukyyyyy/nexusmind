package com.luky.nexusmind.service;

import com.luky.nexusmind.model.DocumentVector;

import java.util.*;

/** Immutable text coordinates survive splitting and retries. Offsets are Java/JS UTF-16 indices. */
public final class GraphBatchPlan {
    private GraphBatchPlan() {}

    public record Part(int chunkId, int start, String text) {
        public int end() {
            return start + text.length();
        }
    }

    public record Batch(int index, List<Part> parts, String before, String after) {}

    public static List<Batch> create(List<DocumentVector> chunks, int limit) {
        if (limit < 1) throw new IllegalArgumentException("批次大小必须为正整数");
        List<Part> all = new ArrayList<>();
        var sorted = new TreeMap<Integer, DocumentVector>();
        chunks.forEach(c -> sorted.putIfAbsent(c.getChunkId(), c));
        sorted.values()
                .forEach(
                        c -> {
                            String text = Objects.toString(c.getTextContent(), "");
                            int start = 0;
                            while (start < text.length()) {
                                int end =
                                        boundary(
                                                text,
                                                start,
                                                Math.min(text.length(), start + limit));
                                all.add(
                                        new Part(
                                                c.getChunkId(), start, text.substring(start, end)));
                                start = end;
                            }
                        });
        List<Batch> batches = new ArrayList<>();
        int i = 0;
        while (i < all.size()) {
            int begin = i, chars = 0;
            List<Part> parts = new ArrayList<>();
            while (i < all.size() && chars + all.get(i).text.length() <= limit) {
                Part p = all.get(i++);
                parts.add(p);
                chars += p.text.length();
            }
            StringBuilder before = new StringBuilder(), after = new StringBuilder();
            for (int j = begin - 1; j >= 0 && before.length() < 1000; j--)
                before.insert(0, all.get(j).text + "\n");
            for (int j = i; j < all.size() && after.length() < 1000; j++)
                after.append(all.get(j).text).append('\n');
            String left = before.substring(Math.max(0, before.length() - 1000));
            String right = after.substring(0, Math.min(1000, after.length()));
            batches.add(new Batch(batches.size(), List.copyOf(parts), left, right));
        }
        return batches;
    }

    private static int boundary(String text, int start, int end) {
        if (end == text.length()) return end;
        for (int i = end - 1; i > start + (end - start) / 2; i--) {
            if ("\n。！？；.!?;".indexOf(text.charAt(i)) >= 0) return i + 1;
        }
        if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return Math.max(start + 1, end);
    }

    public static List<Batch> halve(Batch batch) {
        int remaining = batch.parts.stream().mapToInt(p -> p.text.length()).sum() / 2;
        String whole = batch.parts.stream().map(Part::text).reduce("", String::concat);
        if (remaining > 0 && Character.isHighSurrogate(whole.charAt(remaining - 1))) remaining--;
        if (remaining == 0) return List.of(batch);
        List<Part> a = new ArrayList<>(), b = new ArrayList<>();
        for (Part p : batch.parts) {
            int n = Math.min(remaining, p.text.length());
            if (n > 0) a.add(new Part(p.chunkId, p.start, p.text.substring(0, n)));
            if (n < p.text.length()) b.add(new Part(p.chunkId, p.start + n, p.text.substring(n)));
            remaining -= n;
        }
        return List.of(
                new Batch(batch.index, a, batch.before, context(b, false)),
                new Batch(batch.index, b, context(a, true), batch.after));
    }

    private static String context(List<Part> parts, boolean tail) {
        String s = parts.stream().map(Part::text).reduce("", (a, b) -> a + b);
        return tail
                ? s.substring(Math.max(0, s.length() - 1000))
                : s.substring(0, Math.min(1000, s.length()));
    }

    public static String input(Batch batch) {
        StringBuilder s =
                new StringBuilder("前文（仅辅助，不得作为本批关系证据）：\n")
                        .append(batch.before)
                        .append("\n本批正文（start/end 为原切片中的字符位置）：\n");
        for (Part p : batch.parts)
            s.append("[CHUNK ")
                    .append(p.chunkId)
                    .append(" start=")
                    .append(p.start)
                    .append(" end=")
                    .append(p.end())
                    .append("]\n")
                    .append(p.text)
                    .append('\n');
        return s.append("\n后文（仅辅助，不得作为本批关系证据）：\n").append(batch.after).toString();
    }
}
