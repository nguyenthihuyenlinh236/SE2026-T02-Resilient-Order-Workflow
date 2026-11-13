package com.example.orderworkflow.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.example.orderworkflow.inventory.service.InventoryException;

@Configuration
public class InventoryTracing implements WebMvcConfigurer {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String COMMAND_HEADER = "X-Command-Id";

    public static boolean validHeader(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.chars().allMatch(character -> character >= 32 && character <= 126);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String correlation = request.getHeader(CORRELATION_HEADER);
                if (!validHeader(correlation)) {
                    throw new InventoryException(400, "INVALID_REQUEST",
                            "X-Correlation-Id is required and must contain 1 to 128 printable ASCII characters");
                }
                response.setHeader(CORRELATION_HEADER, correlation);
                String command = request.getHeader(COMMAND_HEADER);
                if (command != null && !validHeader(command)) {
                    throw new InventoryException(400, "INVALID_REQUEST",
                            "X-Command-Id must contain 1 to 128 printable ASCII characters when provided");
                }
                return true;
            }
        }).addPathPatterns("/internal/v1/**");
    }
}
