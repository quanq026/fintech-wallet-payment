package ra.gatewayservice.filter;

import ra.common.utils.RequestSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redis;
    private final Map<String, String> keys;
    private final String internalToken;
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],1) end; return n", Long.class);

    public AuthenticationFilter(ReactiveStringRedisTemplate redis,
                                @Value("${clients.alice}") String alice, @Value("${clients.bob}") String bob,
                                @Value("${clients.admin}") String admin, @Value("${internal.token}") String internalToken) {
        if (alice.length() < 32 || bob.length() < 32 || admin.length() < 32 || internalToken.length() < 32 || new java.util.HashSet<>(List.of(alice, bob, admin)).size() != 3)
            throw new IllegalArgumentException("Use distinct client keys and an internal token of at least 32 characters");
        this.redis = redis;
        this.keys = Map.of("alice", alice, "bob", bob, "admin", admin);
        this.internalToken = internalToken;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        for (String header : List.of("X-Client-Id", "X-Timestamp", "X-Nonce", "X-Signature", "X-Idempotency-Key")) {
            var values = request.getHeaders().get(header);
            if (values != null && values.size() != 1)
                return error(exchange, HttpStatus.BAD_REQUEST, "DUPLICATE_HEADER");
        }
        String user = request.getHeaders().getFirst("X-Client-Id");
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");
        String signature = request.getHeaders().getFirst("X-Signature");
        String idempotencyKey = request.getHeaders().getFirst("X-Idempotency-Key");
        if (user == null || !keys.containsKey(user) || nonce == null || timestamp == null)
            return error(exchange, HttpStatus.UNAUTHORIZED, "SIGNATURE_REQUIRED");
        try {
            ra.common.validation.InputRules.uuid(nonce);
            if (!timestamp.matches("[0-9]{1,12}")) throw new IllegalArgumentException();
            long time = Long.parseLong(timestamp), now = Instant.now().getEpochSecond();
            if (time < now - 60 || time > now + 60) return error(exchange, HttpStatus.UNAUTHORIZED, "EXPIRED_REQUEST");
        } catch (IllegalArgumentException e) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE_HEADERS");
        }
        return DataBufferUtils.join(request.getBody(), 65536).defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(buffer -> {
                    byte[] body = new byte[buffer.readableByteCount()];
                    buffer.read(body);
                    DataBufferUtils.release(buffer);
                    String target = request.getURI().getRawPath() + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());
                    if (!RequestSignature.verify(keys.get(user), request.getMethod().name(), target, timestamp, nonce, idempotencyKey, body, signature))
                        return error(exchange, HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE");
                    String ip = request.getRemoteAddress() == null ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
                    return redis.opsForValue().setIfAbsent("nonce:" + user + ":" + nonce, "1", Duration.ofSeconds(125)).flatMap(fresh -> {
                        if (!fresh) return error(exchange, HttpStatus.CONFLICT, "REPLAYED_REQUEST");
                        return redis.execute(RATE, List.of("rate:user:" + user), List.of()).single()
                                .zipWith(redis.execute(RATE, List.of("rate:ip:" + ip), List.of()).single()).flatMap(rates -> {
                                    if (rates.getT1() > 200 || rates.getT2() > 400)
                                        return error(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT");
                                    var clean = request.mutate().headers(h -> {
                                        h.remove("X-User-Id");
                                        h.remove("X-User-Role");
                                        h.remove("X-Internal-Token");
                                        h.remove("X-Client-Ip");
                                        h.set("X-User-Id", user);
                                        h.set("X-User-Role", user.equals("admin") ? "ADMIN" : "USER");
                                        h.set("X-Internal-Token", internalToken);
                                        h.set("X-Client-Ip", ip);
                                        h.set("X-Request-Id", UUID.randomUUID().toString());
                                    }).build();
                                    var decorated = new ServerHttpRequestDecorator(clean) {
                                        @Override
                                        @NonNull
                                        public Flux<org.springframework.core.io.buffer.DataBuffer> getBody() {
                                            return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
                                        }
                                    };
                                    return chain.filter(exchange.mutate().request(decorated).build());
                                });
                    });
                }).onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class, e -> error(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE"))
                .onErrorResume(e -> error(exchange, HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_UNAVAILABLE"));
    }

    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String code) {
        if (exchange.getResponse().isCommitted()) return Mono.empty();
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
