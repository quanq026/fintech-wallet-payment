package ra.gatewayservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {
    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
                               @Value("${services.engine:http://127.0.0.1:8082}") String engine,
                               @Value("${services.payment:http://127.0.0.1:8083}") String payment,
                               @Value("${services.audit:http://127.0.0.1:8084}") String audit) {
        return builder.routes()
                .route("engine", route -> route.path("/api/accounts/**", "/api/transfers/**",
                        "/api/transactions/**", "/api/reconciliation").uri(engine))
                .route("payment", route -> route.path("/api/deposits/**", "/api/withdrawals/**",
                        "/api/payments/**", "/api/bank/**").uri(payment))
                .route("audit", route -> route.path("/api/audit/**", "/api/notifications/**",
                        "/api/risk/**").uri(audit)).build();
    }
}
