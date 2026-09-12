package com.nameless0422.MenuPick.domain.trend;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 이 프로젝트는 {@code @ConfigurationPropertiesScan}을 쓰지 않고 설정 클래스에서 명시적으로
 * 등록한다({@code MailConfig}와 같은 이유).
 */
@Configuration
@EnableConfigurationProperties(PickTrendProperties.class)
public class TrendConfig {
}
