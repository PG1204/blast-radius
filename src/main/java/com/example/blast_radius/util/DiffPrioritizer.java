package com.example.blast_radius.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a unified diff by file and keeps high-priority hunks (controllers,
 * services, security, repositories) first, filling up to a byte budget.
 * Falls back to raw truncation when no file headers are found.
 */
public final class DiffPrioritizer {

    private DiffPrioritizer() {
    }

    // Paths that signal high-impact code — matched case-insensitively against the file path
    private static final Set<String> HIGH_PRIORITY_KEYWORDS = Set.of(
            "controller", "service", "security", "repository",
            "config", "auth", "filter", "interceptor", "migration"
    );

    // Matches "diff --git a/path b/path" which starts each file section in a unified diff
    private static final Pattern FILE_HEADER = Pattern.compile("^diff --git a/(\\S+)", Pattern.MULTILINE);

    /**
     * Prioritizes critical file hunks within a diff, up to maxLength characters.
     * High-priority files (matching HIGH_PRIORITY_KEYWORDS) are included first,
     * then remaining files fill whatever budget is left.
     * If the diff has no parseable file headers, falls back to raw truncation.
     */
    public static String prioritizeCriticalFiles(String fullDiff, int maxLength) {
        List<FileHunk> hunks = splitByFile(fullDiff);

        // No file headers found — fall back to simple truncation
        if (hunks.size() <= 1) {
            return fullDiff.substring(0, Math.min(fullDiff.length(), maxLength));
        }

        List<FileHunk> highPriority = new ArrayList<>();
        List<FileHunk> lowPriority = new ArrayList<>();

        for (FileHunk hunk : hunks) {
            if (isHighPriority(hunk.filePath)) {
                highPriority.add(hunk);
            } else {
                lowPriority.add(hunk);
            }
        }

        StringBuilder result = new StringBuilder();
        int budget = maxLength;

        // Add high-priority hunks first
        budget = appendHunks(result, highPriority, budget);

        // Fill remaining budget with low-priority hunks
        appendHunks(result, lowPriority, budget);

        return result.toString();
    }

    private static int appendHunks(StringBuilder sb, List<FileHunk> hunks, int budget) {
        for (FileHunk hunk : hunks) {
            if (budget <= 0) {
                break;
            }
            if (hunk.content.length() <= budget) {
                sb.append(hunk.content);
                budget -= hunk.content.length();
            } else {
                // Partial inclusion — truncate this hunk to fit the remaining budget
                sb.append(hunk.content, 0, budget);
                budget = 0;
            }
        }
        return budget;
    }

    private static boolean isHighPriority(String filePath) {
        String lower = filePath.toLowerCase();
        return HIGH_PRIORITY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    static List<FileHunk> splitByFile(String diff) {
        List<FileHunk> hunks = new ArrayList<>();
        Matcher matcher = FILE_HEADER.matcher(diff);

        List<int[]> headerPositions = new ArrayList<>();
        List<String> filePaths = new ArrayList<>();

        while (matcher.find()) {
            headerPositions.add(new int[]{matcher.start(), matcher.end()});
            filePaths.add(matcher.group(1));
        }

        if (headerPositions.isEmpty()) {
            hunks.add(new FileHunk("unknown", diff));
            return hunks;
        }

        for (int i = 0; i < headerPositions.size(); i++) {
            int start = headerPositions.get(i)[0];
            int end = (i + 1 < headerPositions.size())
                    ? headerPositions.get(i + 1)[0]
                    : diff.length();
            hunks.add(new FileHunk(filePaths.get(i), diff.substring(start, end)));
        }

        return hunks;
    }

    static class FileHunk {
        final String filePath;
        final String content;

        FileHunk(String filePath, String content) {
            this.filePath = filePath;
            this.content = content;
        }
    }
}
