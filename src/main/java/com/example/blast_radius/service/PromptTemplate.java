package com.example.blast_radius.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the PR-analysis prompt from the classpath and renders it per request.
 *
 * <p>The template lives in {@code src/main/resources/prompts/pr-analysis.txt} so
 * prompt iteration never touches Java code. Its first line must be a version
 * header ({@code PROMPT_VERSION: vX.Y.Z}) — keeping the version inside the file
 * means it can only change together with the content it describes. The header is
 * stripped from the rendered prompt.
 *
 * <p>Loading is fail-fast: a missing template or version header aborts startup
 * rather than sending an empty prompt upstream.
 */
@Component
public class PromptTemplate {

    static final String TEMPLATE_PATH = "prompts/pr-analysis.txt";
    private static final String VERSION_HEADER = "PROMPT_VERSION:";
    private static final String UNSPECIFIED_BRANCH = "(not specified)";

    private final String version;
    private final String template;

    public PromptTemplate() {
        this(TEMPLATE_PATH);
    }

    PromptTemplate(String templatePath) {
        String raw;
        try {
            raw = new ClassPathResource(templatePath)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Prompt template not readable on classpath: " + templatePath, e);
        }

        int firstLineBreak = raw.indexOf('\n');
        String header = firstLineBreak >= 0 ? raw.substring(0, firstLineBreak) : raw;
        if (!header.startsWith(VERSION_HEADER)) {
            throw new IllegalStateException(
                    "Prompt template must start with a '" + VERSION_HEADER + "' header: " + templatePath);
        }

        String parsedVersion = header.substring(VERSION_HEADER.length()).strip();
        if (parsedVersion.isEmpty()) {
            throw new IllegalStateException(
                    "Prompt template has an empty version header: " + templatePath);
        }

        this.version = parsedVersion;
        this.template = raw.substring(firstLineBreak + 1);
    }

    /** Version declared in the template file, reported back on every analysis response. */
    public String getVersion() {
        return version;
    }

    /** Renders the prompt for one analysis. Placeholder replacement is literal, so diff content is safe. */
    public String render(String baseBranch, String targetBranch, String diff) {
        return template
                .replace("{{BASE_BRANCH}}", orUnspecified(baseBranch))
                .replace("{{TARGET_BRANCH}}", orUnspecified(targetBranch))
                .replace("{{DIFF}}", diff);
    }

    private String orUnspecified(String branch) {
        return (branch == null || branch.isBlank()) ? UNSPECIFIED_BRANCH : branch.strip();
    }
}
