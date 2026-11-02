package org.example.github_api_practice.global.response;

import jakarta.servlet.http.HttpServletResponse;
import org.example.github_api_practice.global.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class JsonResponseWriter {

    private final JsonMapper jsonMapper;

    public JsonResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        write(response, errorCode.getStatus().value(), ApiResponse.failure(errorCode));
    }

    public void write(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
