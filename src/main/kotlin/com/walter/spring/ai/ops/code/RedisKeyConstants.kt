package com.walter.spring.ai.ops.code

class RedisKeyConstants {
    companion object {
        const val REDIS_KEY_APPLICATIONS = "apps"
        const val REDIS_KEY_LLM = "llm"
        const val REDIS_KEY_LLM_API_KEY = "llmKey"
        const val REDIS_KEY_LOKI_URL = "lokiUrl"
        const val REDIS_KEY_GITHUB_URL = "githubUrl"
        const val REDIS_KEY_GIT_REMOTE_TOKEN = "githubToken"
        const val REDIS_KEY_COMMIT_PREFIX = "commit:"
        const val REDIS_KEY_FIRING_PREFIX = "firing:"
    }
}
