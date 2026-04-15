package com.walter.spring.ai.ops.config

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.annotation.EnableAsync

@Configuration
@EnableAsync
class VirtualThreadConfig {
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    fun applicationTaskExecutor(@Value("\${app.async.virtual.max-concurrency:100}") maxConcurrency: Int): AsyncTaskExecutor {
        val executor = SimpleAsyncTaskExecutor("vt-")
        executor.setVirtualThreads(true)
        executor.concurrencyLimit = maxConcurrency
        return executor
    }
}