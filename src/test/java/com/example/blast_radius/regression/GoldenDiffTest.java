package com.example.blast_radius.regression;

import com.example.blast_radius.model.PrAnalysisRequest;
import com.example.blast_radius.model.PrAnalysisResponse;
import com.example.blast_radius.service.AnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test that sends golden diff files through the real analysis pipeline
 * (AnalysisService → GroqClient → LLM) and logs the JSON output for manual inspection.
 *
 * <p>Skipped automatically when GROQ_API_KEY is not set.
 *
 * <h3>Resource layout</h3>
 * <pre>
 *   src/test/resources/golden-diffs/
 *     dto-bug-fix.diff
 *     enum-extension.diff
 *     controller-change.diff
 *     service-logic-change.diff
 *     security-config-change.diff
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class GoldenDiffTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenDiffTest.class);

    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path GOLDEN_DIFFS_DIR =
            Paths.get("src/test/resources/golden-diffs");

    @Autowired
    private AnalysisService analysisService;

    /**
     * Discovers every {@code *.diff} file under {@code golden-diffs/}.
     * Each file becomes a separate parameterized test case.
     */
    static Stream<Path> goldenDiffFiles() throws IOException {
        if (!Files.isDirectory(GOLDEN_DIFFS_DIR)) {
            return Stream.empty();
        }
        return Files.list(GOLDEN_DIFFS_DIR)
                .filter(p -> p.toString().endsWith(".diff"))
                .sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenDiffFiles")
    void analyzeGoldenDiff(Path diffFile) throws Exception {
        String diff = Files.readString(diffFile);
        String fileName = diffFile.getFileName().toString();

        PrAnalysisRequest request = new PrAnalysisRequest();
        request.setBaseBranch("main");
        request.setTargetBranch("feature/golden-test");
        request.setDiff(diff);

        PrAnalysisResponse response = analysisService.analyze(request);

        assertNotNull(response, "Response should never be null");
        assertNotNull(response.getOverallRisk(), "overallRisk should never be null");

        String json = PRETTY_MAPPER.writeValueAsString(response);
        log.info("\n=== Golden result for [{}] ===\n{}", fileName, json);

        // Optionally persist the result for later comparison
        Path resultsDir = Paths.get("src/test/resources/golden-results");
        Files.createDirectories(resultsDir);
        String resultName = fileName.replace(".diff", ".json");
        Files.writeString(resultsDir.resolve(resultName), json);
        log.info("Wrote golden result to golden-results/{}", resultName);
    }
}
