package com.walter.spring.ai.ops.code

class RedisKeyConstants {
    companion object {
        const val REDIS_KEY_APPLICATIONS = "apps"
        const val REDIS_KEY_APP_GIT = "git:"
        const val REDIS_KEY_USAGE_LLM = "usageLlm"
        const val REDIS_KEY_LLM_APIS = "llmApis"
        const val REDIS_KEY_PROMETHEUS_URL = "promUrl"
        const val REDIS_KEY_LOKI_URL = "lokiUrl"
        const val REDIS_KEY_GITHUB_URL = "githubUrl"
        const val REDIS_KEY_GITLAB_URL = "gitlabUrl"
        const val REDIS_KEY_GITHUB_TOKEN = "githubToken"
        const val REDIS_KEY_GITLAB_TOKEN = "gitlabToken"
        const val REDIS_KEY_COMMIT_PREFIX = "commit:"
        const val REDIS_KEY_FIRING_PREFIX = "firing:"
        const val REDIS_KEY_CODE_RISK_PREFIX = "code:"
        const val REDIS_KEY_REPOSITORY_LOCK_PREFIX = "repository:lock:"
        const val REDIS_KEY_REPOSITORY_STATUS_PREFIX = "repository:status:"
    }
}
