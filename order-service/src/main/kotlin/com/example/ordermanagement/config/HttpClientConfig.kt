package com.example.ordermanagement.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * HttpClientConfig — Spring RestClient for service-to-service calls
 *
 * RestClient is Spring 6's replacement for RestTemplate.
 * It's synchronous (matching Temporal activity semantics) and fluent.
 *
 * Activity implementations use RestClient.Builder (injected)
 * to set the base URL from @Value-injected service URLs.
 *
 * WHY NOT WebClient?
 * WebClient is reactive (non-blocking). Temporal activities run on
 * dedicated threads and are fundamentally synchronous. Using blocking
 * code inside a WebClient subscription would require .block() which
 * defeats the purpose. RestClient is the right fit here.
 */
@Configuration
class HttpClientConfig {

    @Bean
    fun restClientBuilder(): RestClient.Builder =
        RestClient.builder()
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
}
