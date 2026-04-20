package com.walter.spring.ai.ops.record;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeRiskIssue(
    String file,
    String line,
    String severity,
    String description,
    String recommendation,
    String codeSnippet
) {}
