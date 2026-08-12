package com.nameless0422.MenuPick.common.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 이 프로젝트는 {@code @ConfigurationPropertiesScan}을 쓰지 않고 설정 클래스에서 명시적으로
 * 등록한다. 메일 설정은 보안과 무관하므로 SecurityConfig의 목록에 끼워 넣지 않고 여기 둔다.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {
}
