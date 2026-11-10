package com.flyo.rescue.config;

import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.OperatorAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("FlyoDataSeeder Positive Profile & Security Boundary Test")
public class FlyoDataSeederProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FlyoDataSeeder.class)
            .withBean(BookingRepository.class, () -> mock(BookingRepository.class))
            .withBean(OperatorAccountRepository.class, () -> mock(OperatorAccountRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

    @Test
    @DisplayName("Dev profile: Seeding bean is instantiated when flag is enabled")
    void testDevProfilePermitsSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev", "flyo.seeding.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Demo profile: Seeding bean is instantiated when flag is enabled")
    void testDemoProfilePermitsSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=demo", "flyo.seeding.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Test profile: Seeding bean is instantiated when flag is enabled")
    void testTestProfilePermitsSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test", "flyo.seeding.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Prod profile: Seeding bean is strictly PROHIBITED and never present")
    void testProdProfileProhibitsSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod", "flyo.seeding.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Default profile (no active profile): Seeding bean is strictly PROHIBITED")
    void testDefaultProfileProhibitsSeeding() {
        contextRunner
                .withPropertyValues("flyo.seeding.enabled=true") // Even if flag is true, default profile blocks
                .run(context -> {
                    assertThat(context).doesNotHaveBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Unexpected profile (e.g. staging): Seeding bean is strictly PROHIBITED")
    void testUnexpectedProfileProhibitsSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=staging", "flyo.seeding.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("flyoDataSeeder");
                });
    }

    @Test
    @DisplayName("Flag false: Seeding bean is strictly disabled across all profiles")
    void testFlagFalseDisablesSeeding() {
        contextRunner
                .withPropertyValues("spring.profiles.active=demo", "flyo.seeding.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("flyoDataSeeder");
                });
    }
}
