package ra.auditservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.context.annotation.Import({ra.common.config.JsonValidationConfig.class, ra.common.exception.GlobalExceptionHandler.class})
public class AuditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}


