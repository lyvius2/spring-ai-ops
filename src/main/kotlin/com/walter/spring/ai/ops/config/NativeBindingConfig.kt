package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.GitRemoteProvider
import com.walter.spring.ai.ops.code.LlmProvider
import com.walter.spring.ai.ops.code.ObservabilityProvider
import com.walter.spring.ai.ops.controller.dto.GitCommitHistoryEntry
import com.walter.spring.ai.ops.record.*
import com.walter.spring.ai.ops.service.dto.*
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.context.annotation.Configuration

// These types are read from Redis or LLM text rather than controller signatures,
// or are rendered by Mustache (index.mustache reads enum properties via reflection).
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding(
    LlmProvider::class,
    GitRemoteProvider::class,
    ObservabilityProvider::class,
    Administrator::class,
    AppConfig::class,
    LlmConfig::class,
    RepositoryStatus::class,
    CodeRiskRecord::class,
    CodeRiskIssue::class,
    CodeReviewRecord::class,
    AnalyzeFiringRecord::class,
    SourceCodeSuggestion::class,
    GitCommitHistoryEntry::class,
    LlmInlineComment::class,
)
class NativeBindingConfig
