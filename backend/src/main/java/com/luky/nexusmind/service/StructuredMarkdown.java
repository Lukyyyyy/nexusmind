package com.luky.nexusmind.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 保留公式和 HTML 表格边界；包含跨行单元格的行组不可拆分。 */
final class StructuredMarkdown {
    private static final Pattern PROTECTED = Pattern.compile(
            "(?ms)^```[^\\n]*\\n.*?^```[^\\n]*|<table\\b[^>]*>.*?</table\\s*>|(?<!\\\\)\\$\\$.*?(?<!\\\\)\\$\\$|\\\\\\[.*?\\\\\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMULA = Pattern.compile(
            "(?s)(?<!\\\\)\\$[^$]+\\$|\\\\\\[.*?\\\\\\]|\\\\\\(.*?\\\\\\)");

    static List<String> blocks(String text) {
        String markdown = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<>();
        var matcher = PROTECTED.matcher(markdown);
        int offset = 0;
        while (matcher.find(offset)) {
            addParagraphs(result, markdown.substring(offset, matcher.start()));
            int end = matcher.end();
            if (matcher.group().toLowerCase(java.util.Locale.ROOT).startsWith("<table")) {
                var tags = Pattern.compile("</?table\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(markdown);
                tags.region(matcher.start(), markdown.length());
                int depth = 0;
                while (tags.find()) {
                    depth += tags.group().startsWith("</") ? -1 : 1;
                    if (depth == 0) { end = tags.end(); break; }
                }
            }
            result.add(markdown.substring(matcher.start(), end));
            offset = end;
        }
        addParagraphs(result, markdown.substring(offset));
        return result;
    }

    private static void addParagraphs(List<String> result, String text) {
        for (String part : text.split("\\n\\s*\\n")) {
            if (!part.isBlank()) result.add(part.trim());
        }
    }

    static boolean containsFormula(String text) { return FORMULA.matcher(text).find(); }
    static boolean containsTable(String text) { return text.toLowerCase(java.util.Locale.ROOT).contains("<table"); }

    static List<String> splitTable(String block, int limit) {
        var document = Jsoup.parseBodyFragment(block);
        document.outputSettings().prettyPrint(false);
        var tables = document.select("table");
        // 嵌套表格无法安全按行切分，保留整个块。
        if (tables.size() != 1) return List.of(block);
        Element table = tables.first();
        List<Element> rows = new ArrayList<>(table.select("tr"));
        if (rows.size() < 2) return List.of(block);
        List<Element> headers = new ArrayList<>();
        while (!rows.isEmpty() && (rows.get(0).parent().tagName().equals("thead")
                || !rows.get(0).select("th").isEmpty())) {
            headers.add(rows.remove(0));
        }
        if (headers.isEmpty()) headers.add(rows.remove(0));
        // 表头跨入正文时保留全表，避免复制悬空的 rowspan。
        for (int i = 0; i < headers.size(); i++) {
            for (Element cell : headers.get(i).select("td[rowspan], th[rowspan]")) {
                int span = rowSpan(cell);
                if (span == 0 || i + span > headers.size()) return List.of(block);
            }
        }
        Element shell = table.clone();
        shell.select("thead, tbody, tfoot, tr").remove();
        Element head = shell.appendElement("thead");
        headers.forEach(row -> head.appendChild(row.clone()));
        List<String> result = new ArrayList<>();
        Element chunk = shell.clone();
        Element body = chunk.appendElement("tbody");
        for (int start = 0; start < rows.size();) {
            int end = start + 1;
            for (int i = start; i < end && i < rows.size(); i++) {
                for (Element cell : rows.get(i).select("td[rowspan], th[rowspan]")) {
                    int span = rowSpan(cell);
                    if (span == 0) return List.of(block);
                    end = Math.min(rows.size(), Math.max(end, i + span));
                }
            }
            StringBuilder group = new StringBuilder();
            for (int i = start; i < end; i++) group.append(rows.get(i).outerHtml());
            if (!body.children().isEmpty() && chunk.outerHtml().length() + group.length() > limit) {
                result.add(chunk.outerHtml());
                chunk = shell.clone();
                body = chunk.appendElement("tbody");
            }
            body.append(group.toString());
            start = end;
        }
        if (!body.children().isEmpty()) result.add(chunk.outerHtml());
        if (result.isEmpty()) return List.of(block);
        // 保留 ParseService 添加的标题上下文及表格外的说明。
        table.replaceWith(new org.jsoup.nodes.TextNode("NEXUS_TABLE_PLACEHOLDER"));
        String surrounding = document.body().html();
        return result.stream().map(html -> surrounding.replace("NEXUS_TABLE_PLACEHOLDER", html)).toList();
    }

    private static int rowSpan(Element cell) {
        try { return Math.max(0, Integer.parseInt(cell.attr("rowspan"))); }
        catch (NumberFormatException ignored) { return 1; }
    }
}
