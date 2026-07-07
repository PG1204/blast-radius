package com.example.blast_radius.service;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateTest {

    private final PromptTemplate template = new PromptTemplate();

    @Test
    void parsesVersionFromHeader() {
        assertEquals("v1.1.0", template.getVersion());
    }

    @Test
    void render_substitutesAllPlaceholders() {
        String prompt = template.render("main", "feature/x", "diff --git a/Foo.java b/Foo.java");

        assertTrue(prompt.contains("base branch \"main\""));
        assertTrue(prompt.contains("branch \"feature/x\""));
        assertTrue(prompt.contains("diff --git a/Foo.java b/Foo.java"));
        assertFalse(prompt.contains("{{BASE_BRANCH}}"));
        assertFalse(prompt.contains("{{TARGET_BRANCH}}"));
        assertFalse(prompt.contains("{{DIFF}}"));
        assertFalse(prompt.contains("PROMPT_VERSION"), "version header must be stripped");
    }

    @Test
    void render_handlesMissingBranches() {
        String prompt = template.render(null, "  ", "some diff");

        assertTrue(prompt.contains("base branch \"(not specified)\""));
        assertTrue(prompt.contains("branch \"(not specified)\""));
        assertTrue(prompt.contains("some diff"));
    }

    @Test
    void render_treatsPlaceholderLikeDiffContentLiterally() {
        // Diff is substituted last, so placeholder-looking diff content survives verbatim
        String prompt = template.render("main", "feature/x", "uses {{BASE_BRANCH}} token");

        assertTrue(prompt.contains("uses {{BASE_BRANCH}} token"));
    }

    @Test
    void failsFast_whenTemplateMissing() {
        assertThrows(UncheckedIOException.class,
                () -> new PromptTemplate("prompts/does-not-exist.txt"));
    }
}
