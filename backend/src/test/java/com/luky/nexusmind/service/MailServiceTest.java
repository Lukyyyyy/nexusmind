package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.model.EmailDelivery;
import com.luky.nexusmind.model.SmtpSettings;
import com.luky.nexusmind.repository.SmtpSettingsRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MailServiceTest {
    private final MailService service = new MailService(
            null, null, null,
            new ObjectMapper(), "tencent-ses", "", "", "ap-hongkong", "noreply@example.com",
            1, 2, 3, 4, 5, 6, 7);

    @Test
    void readsTemplateVariables() throws Exception {
        EmailDelivery verification = new EmailDelivery();
        verification.setTemplateKind(EmailDelivery.TemplateKind.VERIFICATION);
        verification.setBody("{\"code\":\"012345\",\"minutes\":\"10\"}");
        assertThat(service.templateData(verification))
                .containsEntry("code", "012345")
                .containsEntry("minutes", "10");

        EmailDelivery application = new EmailDelivery();
        application.setTemplateKind(EmailDelivery.TemplateKind.ORGANIZATION_APPLICATION);
        application.setBody("{\"applicant\":\"张三\",\"organization\":\"研发部\",\"reason\":\"项目协作\"}");
        assertThat(service.templateData(application))
                .containsEntry("applicant", "张三")
                .containsEntry("organization", "研发部")
                .containsEntry("reason", "项目协作");
    }

    @Test
    void respectsGlobalServiceSwitch() {
        SmtpSettings settings = new SmtpSettings();
        settings.setEnabled(false);
        SmtpSettingsRepository repository = (SmtpSettingsRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{SmtpSettingsRepository.class},
                (proxy, method, args) -> method.getName().equals("findById") ? Optional.of(settings) : null);
        MailService disabled = new MailService(
                null, repository, null, new ObjectMapper(), "tencent-ses", "", "", "ap-hongkong", "noreply@example.com",
                1, 2, 3, 4, 5, 6, 7);

        assertThat(disabled.isEnabled()).isFalse();
    }
}
