package com.flyo.rescue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class FlyoRescueApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlyoRescueApplication.class, args);
    }

    @Configuration
    static class ScreenshotResourceConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            Path agentScreenshots = Paths.get("../agent/screenshots").toAbsolutePath().normalize();
            if (!agentScreenshots.toFile().exists()) {
                agentScreenshots = Paths.get("agent/screenshots").toAbsolutePath().normalize();
            }
            if (!agentScreenshots.toFile().exists()) {
                agentScreenshots = Paths.get("flyo-rescue-demo/agent/screenshots").toAbsolutePath().normalize();
            }
            registry.addResourceHandler("/screenshots/**")
                    .addResourceLocations("file:" + agentScreenshots.toString() + "/");
        }
    }
}
