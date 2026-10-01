package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.application.port.out.TemplateEnginePort;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.out.template.ThymeleafTemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ThymeleafTemplateAdapterTest {

    private ThymeleafTemplateAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ThymeleafTemplateAdapter();
    }

    @Test
    @DisplayName("Should render OTP email in English with safe variables and escaped content")
    void shouldRenderOtpEmailInEnglish() {
        Map<String, Object> data = Map.of(
                "otpCode", "981245",
                "expiresInSeconds", 180,
                "unauthorizedParam", "ATTACK"
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.OTP_REQUESTED, Channel.EMAIL, "en", data
        );

        assertThat(rendered.subjectOrTitle()).isEqualTo("Security Verification Code");
        assertThat(rendered.templateVersion()).isEqualTo("v1.0.0");
        assertThat(rendered.body()).contains("981245");
        assertThat(rendered.body()).contains("180");
        assertThat(rendered.body()).doesNotContain("ATTACK");
    }

    @Test
    @DisplayName("Should render OTP email in Spanish with correct locale")
    void shouldRenderOtpEmailInSpanish() {
        Map<String, Object> data = Map.of(
                "otpCode", "445566",
                "expiresInSeconds", 300
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.OTP_REQUESTED, Channel.EMAIL, "es", data
        );

        assertThat(rendered.subjectOrTitle()).isEqualTo("Código de Seguridad");
        assertThat(rendered.body()).contains("Tu código de seguridad temporal es:");
        assertThat(rendered.body()).contains("445566");
    }

    @Test
    @DisplayName("Should escape potential XSS HTML script tags in email templates")
    void shouldEscapeXssInEmailTemplates() {
        Map<String, Object> data = Map.of(
                "amount", "150.00",
                "currency", "USD",
                "cardLast4", "4821",
                "merchantName", "<script>alert('pwned')</script>"
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.FRAUD_SUSPECTED, Channel.EMAIL, "en", data
        );

        assertThat(rendered.body()).doesNotContain("<script>");
        assertThat(rendered.body()).contains("&lt;script&gt;");
        assertThat(rendered.body()).contains("&lt;/script&gt;");
    }

    @Test
    @DisplayName("Should fallback to English when unsupported locale is requested")
    void shouldFallbackToEnglishOnUnknownLocale() {
        Map<String, Object> data = Map.of(
                "amount", "500.00",
                "currency", "EUR",
                "accountLast4", "9912",
                "beneficiaryName", "Alice Smith"
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.TRANSFER_COMPLETED, Channel.EMAIL, "de", data
        );

        assertThat(rendered.subjectOrTitle()).isEqualTo("Transfer Confirmation");
        assertThat(rendered.body()).contains("Your transfer has been processed successfully.");
    }

    @Test
    @DisplayName("Should render SMS in plain text mode")
    void shouldRenderSmsInTextMode() {
        Map<String, Object> data = Map.of(
                "otpCode", "123456",
                "expiresInSeconds", 60
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.OTP_REQUESTED, Channel.SMS, "en", data
        );

        assertThat(rendered.body()).isEqualTo("Pigeon Bank: Your security code is 123456. Expires in 60s. Do not share.");
    }

    @Test
    @DisplayName("Should render Push notification title and body separately")
    void shouldRenderPushNotification() {
        Map<String, Object> data = Map.of(
                "amount", "89.50",
                "currency", "USD",
                "cardLast4", "1122",
                "merchantName", "Amazon",
                "reasonCode", "LIMIT_EXCEEDED"
        );

        TemplateEnginePort.RenderedMessage rendered = adapter.render(
                EventType.PURCHASE_DECLINED, Channel.PUSH, "en", data
        );

        assertThat(rendered.subjectOrTitle()).isEqualTo("Purchase Declined");
        assertThat(rendered.body()).isEqualTo("Purchase of 89.50 USD at Amazon declined on card *1122.");
    }

    @Test
    @DisplayName("Verify that NO template file in src/main/resources/templates contains th:utext (Anti-SSTI rule)")
    void verifyNoTemplateContainsUtext() throws IOException {
        Path templateDir = Paths.get("src/main/resources/templates");
        try (Stream<Path> paths = Files.walk(templateDir)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String content = Files.readString(file);
                    assertThat(content)
                            .withFailMessage("Template %s contains forbidden 'th:utext' directive!", file)
                            .doesNotContain("th:utext");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
