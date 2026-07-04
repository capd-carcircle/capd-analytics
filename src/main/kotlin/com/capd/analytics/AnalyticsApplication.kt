package com.capd.analytics

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AnalyticsApplication

fun main(args: Array<String>) {
    runApplication<AnalyticsApplication>(*args)
}
