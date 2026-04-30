package de.jplag.java_cpg.ai.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import de.jplag.java_cpg.ParsingException;
import de.jplag.java_cpg.ai.VisitedLinesRecorder;

class DeadCodeCliTest {

    @NotNull
    private static File resourceDir(@NotNull String relative) {
        return new File(Objects.requireNonNull(DeadCodeCliTest.class.getClassLoader().getResource(relative)).getFile());
    }

    @Test
    void analyzeReportsDeadLine() throws ParsingException, InterruptedException {
        VisitedLinesRecorder recorder = DeadCodeCli.analyze(resourceDir("java/ai/deadCode2"));
        String json = DeadCodeCli.toJson(recorder);
        // deadCode2's Main.java has unreachable code on line 9 (statement after a return).
        assertTrue(json.startsWith("{"), json);
        assertTrue(json.endsWith("}"), json);
        assertTrue(json.contains("\"files\":["), json);
        assertTrue(json.contains("\"deadLines\""), json);
        assertTrue(json.contains("\"totalDeadLines\""), json);
        assertTrue(json.contains("Main.java"), json);
        assertTrue(json.contains("9"), "expected line 9 to appear in dead lines: " + json);
        assertFalse(json.contains("\"totalDeadLines\":0"), "expected non-zero dead lines: " + json);
    }

    @Test
    void mainPrintsJsonToStdout() throws Exception {
        File sourceDir = resourceDir("java/ai/deadCode2");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            DeadCodeCli.main(new String[] {sourceDir.getAbsolutePath()});
        } finally {
            System.setOut(originalOut);
        }
        // analysis-internal prints are suppressed; the only output is the JSON line.
        String stdout = captured.toString(StandardCharsets.UTF_8).trim();
        assertTrue(stdout.startsWith("{") && stdout.endsWith("}"), "stdout should be a single JSON document: " + stdout);
        assertTrue(stdout.contains("\"deadLines\""), stdout);
        assertTrue(stdout.contains("Main.java"), stdout);
    }

    @Test
    void toJsonOnEmptyRecorderEmitsEmptyReport() {
        String json = DeadCodeCli.toJson(new VisitedLinesRecorder());
        assertEquals("{\"files\":[],\"totalDeadLines\":0}", json);
    }
}
