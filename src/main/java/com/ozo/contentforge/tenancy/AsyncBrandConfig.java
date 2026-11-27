package com.ozo.contentforge.tenancy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/** {@code @Async} work keeps the caller's brand (Spring Boot applies a single TaskDecorator bean to its executor). */
@Configuration
public class AsyncBrandConfig {

    @Bean
    public TaskDecorator brandContextTaskDecorator() {
        return BrandContext::wrap;
    }
}
