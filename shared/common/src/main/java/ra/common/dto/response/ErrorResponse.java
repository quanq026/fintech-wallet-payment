package ra.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String field) {
    public static ErrorResponse code(String code) {
        return new ErrorResponse(code, null, null);
    }
}
