package com.walter.spring.ai.ops

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@SpringBootApplication
@EnableFeignClients
class SpringAiOpsApplication

fun main(args: Array<String>) {
    runApplication<SpringAiOpsApplication>(*args)
}
