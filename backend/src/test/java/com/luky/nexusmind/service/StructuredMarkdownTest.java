package com.luky.nexusmind.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StructuredMarkdownTest {
    @Test
    void keepsDisplayAndInlineFormulaWholePastLimit() {
        String formula = "$$\n\\frac{" + "a+".repeat(100) + "b}{c}\n\n+ d\n$$";
        List<String> chunks = ReflectionTestUtils.invokeMethod(new ParseService(), "splitMarkdownIntoChunks", "标题\n\n" + formula + "\n\n结尾", 64);
        assertTrue(chunks.stream().anyMatch(c -> c.contains(formula)));
        String inline = "说明 $" + "x+".repeat(50) + "y$ 结束";
        chunks = ReflectionTestUtils.invokeMethod(new ParseService(), "splitMarkdownIntoChunks", inline, 64);
        assertEquals(List.of(inline), chunks);
    }

    @Test
    void splitsHtmlTableRepeatingHeadersAndKeepingRowspanGroups() {
        String table = "<table><thead><tr><th>模型</th><th>得分</th></tr></thead><tbody>"
                + "<tr><td rowspan='2'>A</td><td>11</td></tr><tr><td>12</td></tr>"
                + "<tr><td>B</td><td>21</td></tr><tr><td>C</td><td>31</td></tr></tbody></table>";
        var chunks = StructuredMarkdown.splitTable(table, 160);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.contains("<th>模型</th>")));
        assertTrue(chunks.get(0).contains("11"));
        assertTrue(chunks.get(0).contains("12"));
        assertEquals(4, chunks.stream().mapToInt(c -> Jsoup.parse(c).select("tbody tr").size()).sum());
    }

    @Test
    void keepsNestedTablesWhole() {
        String nested = "<table><tr><td>外表<table><tr><td>内表</td></tr></table></td></tr></table>";
        assertEquals(List.of(nested), StructuredMarkdown.blocks(nested));
        assertEquals(List.of(nested), StructuredMarkdown.splitTable(nested, 20));
    }

    @Test
    void preservesBlankLinesInsideTableAndFormulaAndCode() {
        String table = "<table>\n\n<tr><td>A</td></tr>\n\n</table>";
        String code = "```text\n\n$$ not math\n```";
        assertEquals(List.of("intro", table, code), StructuredMarkdown.blocks("intro\n\n" + table + "\n\n" + code));
    }
}
