package com.aijiaoxue.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiWebConfiguration implements WebMvcConfigurer {
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1", type -> type.getPackageName().equals("com.aijiaoxue") || type.getPackageName().startsWith("com.aijiaoxue."));
    }
}
