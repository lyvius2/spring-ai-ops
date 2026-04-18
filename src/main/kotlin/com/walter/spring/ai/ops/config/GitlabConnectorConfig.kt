package com.walter.spring.ai.ops.config

import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_TOKEN
import com.walter.spring.ai.ops.code.RedisKeyConstants.Companion.REDIS_KEY_GITLAB_URL
import com.walter.spring.ai.ops.config.base.DynamicConnectorConfig
import com.walter.spring.ai.ops.util.CryptoProvider
import feign.RequestInterceptor
import feign.RequestTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

class GitlabConnectorConfig(
    override val redisTemplate: StringRedisTemplate,
    private val cryptoProvider: CryptoProvider,
    @Value("\${gitlab.url:https://gitlab.com/api/v4}") override val configuredUrl: String,
    @Value("\${gitlab.access-token:}") private val configuredToken: String,
    @Value("\${feign.gitlab.connect-timeout:5000}") override val connectTimeout: Long,
    @Value("\${feign.gitlab.read-timeout:30000}") override val readTimeout: Long,
) : DynamicConnectorConfig() {

    companion object {
        const val PLACEHOLDER_URL = "https://gitlab.com/api/v4"
        private val PROJECT_PATH_REGEX = Regex("""/projects/([^?#]+)/repository/""")
    }

    override val placeholderUrl: String = PLACEHOLDER_URL
    override val redisUrlKey: String = REDIS_KEY_GITLAB_URL

    @Bean
    fun gitlabAuthInterceptor(): RequestInterceptor = RequestInterceptor { template ->
        val token = redisTemplate.opsForValue().get(REDIS_KEY_GITLAB_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { cryptoProvider.decrypt(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: configuredToken.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            template.header("PRIVATE-TOKEN", token)
        }
    }

    /**
     * Re-encodes '/' as '%2F' in the GitLab project path segment.
     *
     * Spring Cloud Feign's Spring MVC encoder decodes '%2F' back to '/' when
     * expanding @PathVariable values into the URL template, causing GitLab to
     * interpret 'namespace/project' as two separate path segments and return 404.
     *
     * This interceptor detects paths of the form /projects/{anything}/repository/
     * and re-encodes any literal '/' inside the project path portion.
     */
    @Bean
    fun gitlabProjectPathEncodingInterceptor(): RequestInterceptor = RequestInterceptor { template: RequestTemplate ->
        val url = template.url()
        val match = PROJECT_PATH_REGEX.find(url) ?: return@RequestInterceptor
        val rawProjectPath = match.groupValues[1]           // e.g. "walter-project/next"
        if (!rawProjectPath.contains('/')) return@RequestInterceptor  // already single segment
        val encodedProjectPath = rawProjectPath.replace("/", "%2F")
        val fixedUrl = url.replace(match.value, "/projects/$encodedProjectPath/repository/")
        template.uri(fixedUrl)
    }
}
