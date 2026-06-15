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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class GoldenDiffTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenDiffTest.class);

    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path GOLDEN_DIFFS_DIR =
            Paths.get("src/test/resources/golden-diffs");

    private static final Path GOLDEN_RESULTS_DIR =
            Paths.get("src/test/resources/golden-results");

    private static final boolean UPDATE_MODE =
            Boolean.getBoolean("golden.update");

    @Autowired
    private AnalysisService analysisService;

    static Stream<Path> goldenDiffFiles() throws IOException {
        if (!Files.isDirectory(GOLDEN_DIFFS_DIR)) {
            System.out.println("GoldenDiffTest: directory not found: " + GOLDEN_DIFFS_DIR.toAbsolutePath());
            return Stream.empty();
        }
        try (Stream<Path> stream = Files.list(GOLDEN_DIFFS_DIR)) {
            var files = stream
                    .filter(p -> p.toString().endsWith(".diff"))
                    .sorted()
                    .toList();
            System.out.println("GoldenDiffTest: discovered diff files: " + files);
            return files.stream();
        }
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

        PrAnalysisResponse actual = analysisService.analyze(request);

        assertNotNull(actual, "Response should never be null");
        assertNotNull(actual.getOverallRisk(), "overallRisk should never be null");

        String resultName = fileName.replace(".diff", ".json");
        Path goldenPath = GOLDEN_RESULTS_DIR.resolve(resultName);

        if (UPDATE_MODE || !Files.exists(goldenPath)) {
            Files.createDirectories(GOLDEN_RESULTS_DIR);
            String json = PRETTY_MAPPER.writeValueAsString(actual);
            Files.writeString(goldenPath, json);
            log.info("GoldenDiffTest[update]: wrote {}", goldenPath);
            return;
        }

        String expectedJson = Files.readString(goldenPath);
        PrAnalysisResponse expected = MAPPER.readValue(expectedJson, PrAnalysisResponse.class);

        assertSameRisk(expected, actual);
        assertImpactAreasContainAnchors(expected, actual);
        assertSuggestedTestsReasonable(expected, actual);
    }

    private void assertSameRisk(PrAnalysisResponse expected, PrAnalysisResponse actual) {
        assertEquals(expected.getOverallRisk(), actual.getOverallRisk(),
                "overallRisk mismatch");
    }

    private void assertImpactAreasContainAnchors(PrAnalysisResponse expected, PrAnalysisResponse actual) {
        List<String> expectedAreas = expected.getImpactAreas();
        if (expectedAreas == null || expectedAreas.isEmpty()) {
            return;
        }
        List<String> actualAreas = actual.getImpactAreas();
        if (actualAreas == null || actualAreas.isEmpty()) {
            fail("Expected impactAreas to contain anchors but actual impactAreas is empty");
            return;
        }

        for (String expectedEntry : expectedAreas) {
            if (expectedEntry == null || expectedEntry.isBlank()) {
                continue;
            }
            String anchor = expectedEntry.trim().split("\\s+", 2)[0];
            String anchorLower = anchor.toLowerCase(Locale.ROOT);
            boolean matched = actualAreas.stream()
                    .filter(a -> a != null)
                    .anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(anchorLower));
            assertTrue(matched,
                    "No actual impactArea contains anchor '" + anchor + "' (actual: " + actualAreas + ")");
        }
    }

    private void assertSuggestedTestsReasonable(PrAnalysisResponse expected, PrAnalysisResponse actual) {
        List<String> expectedTests = expected.getSuggestedTests();
        if (expectedTests == null || expectedTests.isEmpty()) {
            return;
        }
        List<String> actualTests = actual.getSuggestedTests();
        if (actualTests == null || actualTests.isEmpty()) {
            fail("Expected suggestedTests but actual suggestedTests is empty");
            return;
        }
        int minRequired = Math.max(1, expectedTests.size() - 1);
        assertTrue(actualTests.size() >= minRequired,
                "Expected at least " + minRequired + " suggestedTests, got " + actualTests.size());
    }
}
