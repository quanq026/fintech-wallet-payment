package ra.transactionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import ra.common.config.JsonValidationConfig;
import ra.common.exception.GlobalExceptionHandler;

@SpringBootApplication
@EnableScheduling
@Import({JsonValidationConfig.class, GlobalExceptionHandler.class})
public class TransactionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
