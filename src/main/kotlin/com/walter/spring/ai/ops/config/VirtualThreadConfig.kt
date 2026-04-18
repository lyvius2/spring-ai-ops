package com.walter.spring.ai.ops.config

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import java.util.concurrent.Semaphore

@Configuration
@EnableAsync
class VirtualThreadConfig {

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    fun applicationTaskExecutor(): AsyncTaskExecutor {
        val executor = SimpleAsyncTaskExecutor("vt-")
        executor.setVirtualThreads(true)
        // concurrencyLimit intentionally not set (-1 = unlimited)
        return executor
    }

    @Bean
    fun llmRateLimiter(@Value("\${app.async.virtual.llm-max-concurrency:10}") maxConcurrency: Int): Semaphore {
        return Semaphore(maxConcurrency, true)
    }
}