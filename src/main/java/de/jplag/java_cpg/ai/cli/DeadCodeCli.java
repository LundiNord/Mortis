package de.jplag.java_cpg.ai.cli;

import de.fraunhofer.aisec.cpg.ConfigurationException;
import de.fraunhofer.aisec.cpg.InferenceConfiguration;
import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.graph.Component;
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration;
import de.fraunhofer.aisec.cpg.passes.ControlDependenceGraphPass;
import de.fraunhofer.aisec.cpg.passes.ControlFlowSensitiveDFGPass;
import de.fraunhofer.aisec.cpg.passes.DFGPass;
import de.fraunhofer.aisec.cpg.passes.DynamicInvokeResolver;
import de.fraunhofer.aisec.cpg.passes.EvaluationOrderGraphPass;
import de.fraunhofer.aisec.cpg.passes.ImportResolver;
import de.fraunhofer.aisec.cpg.passes.JavaExternalTypeHierarchyResolver;
import de.fraunhofer.aisec.cpg.passes.JavaExtraPass;
import de.fraunhofer.aisec.cpg.passes.JavaImportResolver;
import de.fraunhofer.aisec.cpg.passes.Pass;
import de.fraunhofer.aisec.cpg.passes.ProgramDependenceGraphPass;
import de.fraunhofer.aisec.cpg.passes.ResolveCallExpressionAmbiguityPass;
import de.fraunhofer.aisec.cpg.passes.ResolveMemberExpressionAmbiguityPass;
import de.fraunhofer.aisec.cpg.passes.SymbolResolver;
import de.fraunhofer.aisec.cpg.passes.TypeHierarchyResolver;
import de.fraunhofer.aisec.cpg.passes.TypeResolver;
import de.jplag.java_cpg.ParsingException;
import de.jplag.java_cpg.ai.AbstractInterpretation;
import de.jplag.java_cpg.ai.VisitedLinesRecorder;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Command-line entry point for the dead-code analyzer. Run with the path to a Java source directory; the analysis
 * result is written as JSON to standard output.
 * @author ujiqk
 * @version 1.0
 */
public final class DeadCodeCli {

    private DeadCodeCli() {
    }

    /**
     * @param args command-line arguments. Expects a single argument: the path to a Java source directory.
     * @throws ParsingException if the source code cannot be parsed.
     * @throws InterruptedException if the analysis is interrupted.
     */
    public static void main(String[] args) throws ParsingException, InterruptedException {
        if (args.length != 1) {
            System.err.println("Usage: java -jar ai_dead_code.jar <source-directory>");
            System.exit(1);
        }
        File sourceDir = new File(args[0]);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            System.err.println("Not a directory: " + sourceDir);
            System.exit(2);
        }
        VisitedLinesRecorder recorder = analyze(sourceDir);
        System.out.println(toJson(recorder));
    }

    /**
     * Runs the abstract interpretation against the given source directory and returns the recorder containing visited
     * and dead lines.
     * @param sourceDir directory containing the Java source files to analyze.
     * @return a {@link VisitedLinesRecorder} populated with analysis results.
     * @throws ParsingException if the source code cannot be parsed.
     * @throws InterruptedException if the analysis is interrupted.
     */
    @NotNull
    public static VisitedLinesRecorder analyze(@NotNull File sourceDir) throws ParsingException, InterruptedException {
        TranslationResult result = translate(Set.of(sourceDir));
        VisitedLinesRecorder recorder = new VisitedLinesRecorder();
        AbstractInterpretation interpretation = new AbstractInterpretation(recorder, false);

        // Suppress noisy debug prints emitted by the walker so stdout only contains the JSON report.
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
        try {
            Component comp = result.getComponents().getFirst();
            int translationUnitCount = comp.getTranslationUnits().size();
            for (TranslationUnitDeclaration translationUnit : comp.getTranslationUnits()) {
                var parent = translationUnit.getName().getParent();
                boolean isMainTu = parent != null && parent.getLocalName().endsWith("Main");
                if (isMainTu || translationUnitCount == 1) {
                    interpretation.runMain(translationUnit);
                }
            }
        } finally {
            System.setOut(originalOut);
        }
        return recorder;
    }

    /**
     * Renders the recorder's findings as a JSON document.
     * @param recorder the recorder produced by {@link #analyze(File)}.
     * @return a JSON string describing the dead lines found per file.
     */
    @NotNull
    public static String toJson(@NotNull VisitedLinesRecorder recorder) {
        Map<URI, Set<Integer>> nonVisited = recorder.getNonVisitedLines();
        List<Map.Entry<URI, Set<Integer>>> entries = new ArrayList<>(nonVisited.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().toString()));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"files\":[");
        boolean firstFile = true;
        int totalDead = 0;
        for (Map.Entry<URI, Set<Integer>> entry : entries) {
            List<Integer> sortedLines = entry.getValue().stream().sorted().toList();
            if (sortedLines.isEmpty()) {
                continue;
            }
            if (!firstFile) {
                sb.append(',');
            }
            firstFile = false;
            sb.append("{\"path\":");
            appendJsonString(sb, entry.getKey().toString());
            sb.append(",\"deadLines\":[");
            for (int i = 0; i < sortedLines.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(sortedLines.get(i));
            }
            sb.append("]}");
            totalDead += sortedLines.size();
        }
        sb.append("],\"totalDeadLines\":").append(totalDead).append('}');
        return sb.toString();
    }

    private static void appendJsonString(@NotNull StringBuilder sb, @NotNull String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @NotNull
    private static TranslationResult translate(@NotNull Set<File> files) throws ParsingException, InterruptedException {
        InferenceConfiguration inferenceConfiguration = InferenceConfiguration.builder().inferRecords(true).inferDfgForUnresolvedCalls(true).build();
        try {
            TranslationConfiguration.Builder configBuilder = new TranslationConfiguration.Builder().inferenceConfiguration(inferenceConfiguration)
                    .sourceLocations(files.toArray(new File[] {})).registerLanguage("de.fraunhofer.aisec.cpg.frontends.java.JavaLanguage");
            List<Class<? extends Pass<?>>> passClasses = List.of(TypeResolver.class, TypeHierarchyResolver.class,
                    JavaExternalTypeHierarchyResolver.class, JavaImportResolver.class, ImportResolver.class, SymbolResolver.class,
                    DynamicInvokeResolver.class, EvaluationOrderGraphPass.class, ControlDependenceGraphPass.class, ProgramDependenceGraphPass.class,
                    DFGPass.class, JavaExtraPass.class, ControlFlowSensitiveDFGPass.class, ResolveCallExpressionAmbiguityPass.class,
                    ResolveMemberExpressionAmbiguityPass.class);
            for (Class<? extends Pass<?>> passClass : passClasses) {
                configBuilder.registerPass(getKClass(passClass));
            }
            return TranslationManager.builder().config(configBuilder.build()).build().analyze().get();
        } catch (ExecutionException | ConfigurationException e) {
            throw new ParsingException(List.copyOf(files).getFirst(), e);
        }
    }

    @NotNull
    private static <T extends Pass<?>> KClass<T> getKClass(@NotNull Class<T> javaPassClass) {
        return JvmClassMappingKt.getKotlinClass(javaPassClass);
    }
}
