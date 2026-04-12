package com.walter.spring.ai.ops.record;

public record ChangedFile(
    String filename,
    String status,
    int additions,
    int deletions,
    String patch
) { }
