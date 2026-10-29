package ra.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonValidationConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToEnable(
                        JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .postConfigurer(mapper -> {
                    mapper.getFactory().setStreamReadConstraints(
                            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                    .maxNestingDepth(16).maxStringLength(65536)
                                    .maxNumberLength(19).maxNameLength(100).build());
                    for (var shape : new CoercionInputShape[]{CoercionInputShape.String,
                            CoercionInputShape.EmptyString, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
                        mapper.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
                    }
                    for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer,
                            CoercionInputShape.Float, CoercionInputShape.Boolean}) {
                        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
                    }
                });
    }
}
