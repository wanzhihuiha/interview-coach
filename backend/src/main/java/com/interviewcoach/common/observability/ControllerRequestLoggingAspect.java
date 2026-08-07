package com.interviewcoach.common.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewcoach.common.response.ApiResponse;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

/**
 * 在 Controller 真正执行前记录已完成参数绑定的 DEBUG 入参，并补充请求结束摘要。
 * 密码、令牌、验证码等凭据字段始终掩码，文件只记录元数据。
 *
 * <p>该切面执行时认证和 MVC 参数绑定已经完成，因此可补齐入口过滤器拿不到的 handler、
 * userId 和结构化入参；异常不在此处吞掉，由全局异常处理器统一转换并回填业务码。</p>
 */
@Slf4j
@Aspect
@Component
public class ControllerRequestLoggingAspect {

    private static final Set<String> CREDENTIAL_NAMES = Set.of(
            "password", "passwd", "pwd", "token", "auth", "authorization", "cookie", "secret",
            "apikey", "accesskey", "credential", "smscode", "verifycode",
            "verificationcode", "code");

    private final ObjectMapper objectMapper;
    private final int maxPayloadLength;

    public ControllerRequestLoggingAspect(
            ObjectMapper objectMapper,
            @Value("${observability.debug.max-payload-length:16000}") int maxPayloadLength) {
        this.objectMapper = objectMapper;
        this.maxPayloadLength = Math.max(maxPayloadLength, 1000);
    }

    /**
     * 拦截所有 RestController：执行前写入处理器和用户信息，正常返回后记录业务响应码。
     *
     * <p>请求参数只在 DEBUG 输出。{@link ProceedingJoinPoint#proceed()} 抛出的异常保持原样向外传播，
     * 避免切面和全局异常处理器重复决定响应及打印堆栈。</p>
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object logControllerRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String handler = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        HttpServletRequest request = currentRequest();
        String userId = currentUserId();
        HttpRequestDiagnostics.setHandler(request, handler);
        HttpRequestDiagnostics.setUserId(request, userId);

        if (log.isDebugEnabled()) {
            log.debug("[HTTP] 请求参数: handler={}, userId={}, args={}",
                    handler, userId, serializeArguments(method, joinPoint.getArgs()));
        }

        Object result = joinPoint.proceed();
        if (result instanceof ApiResponse<?> response) {
            HttpRequestDiagnostics.setBusinessCode(request, response.getCode());
        }
        return result;
    }

    /**
     * 使用 Spring MVC 注解名或反射参数名构造结构化入参，并在序列化前递归掩码凭据字段。
     * 最终文本受统一长度上限约束，防止开发日志被超大请求撑满。
     */
    private String serializeArguments(Method method, Object[] arguments) {
        ObjectNode root = objectMapper.createObjectNode();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < arguments.length; index++) {
            Parameter parameter = index < parameters.length ? parameters[index] : null;
            String name = parameterName(parameter, index);
            JsonNode value = isCredentialName(name)
                    ? objectMapper.getNodeFactory().textNode("***")
                    : argumentNode(arguments[index]);
            redactCredentials(value);
            root.set(uniqueName(root, name, index), value);
        }
        try {
            return abbreviate(objectMapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            return "<request arguments could not be serialized>";
        }
    }

    /**
     * 将业务参数转换为 JSON；文件、字节流和 Servlet 基础设施对象只输出安全元数据或类型。
     */
    private JsonNode argumentNode(Object argument) {
        if (argument == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if (argument instanceof MultipartFile file) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("field", file.getName());
            node.put("originalFilename", file.getOriginalFilename());
            node.put("contentType", file.getContentType());
            node.put("size", file.getSize());
            return node;
        }
        if (argument instanceof Part part) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("field", part.getName());
            node.put("submittedFilename", part.getSubmittedFileName());
            node.put("contentType", part.getContentType());
            node.put("size", part.getSize());
            return node;
        }
        if (argument instanceof byte[] bytes) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "byte[]");
            node.put("length", bytes.length);
            return node;
        }
        if (isInfrastructureArgument(argument)) {
            return objectMapper.getNodeFactory().textNode("<" + argument.getClass().getSimpleName() + ">");
        }
        try {
            return objectMapper.valueToTree(argument);
        } catch (IllegalArgumentException e) {
            return objectMapper.getNodeFactory().textNode(
                    "<" + argument.getClass().getSimpleName() + ": serialization failed>");
        }
    }

    private boolean isInfrastructureArgument(Object argument) {
        return argument instanceof ServletRequest
                || argument instanceof ServletResponse
                || argument instanceof Errors
                || argument instanceof Principal
                || argument instanceof Authentication
                || argument instanceof InputStream
                || argument instanceof OutputStream
                || argument instanceof Reader
                || argument instanceof Writer;
    }

    /**
     * 递归处理对象和数组中的凭据字段。即使开发环境开启 DEBUG，这些字段也不会输出原文。
     */
    private void redactCredentials(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (isCredentialName(fieldName)) {
                    objectNode.put(fieldName, "***");
                } else {
                    redactCredentials(objectNode.get(fieldName));
                }
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::redactCredentials);
        }
    }

    /**
     * 统一去除大小写和分隔符后匹配凭据名及其后缀，兼容 DTO 中的 accessToken 等组合字段。
     */
    private boolean isCredentialName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return CREDENTIAL_NAMES.stream().anyMatch(
                credential -> normalized.equals(credential) || normalized.endsWith(credential));
    }

    private String parameterName(Parameter parameter, int index) {
        if (parameter == null) {
            return "arg" + index;
        }
        String annotationName = annotationName(parameter);
        if (annotationName != null) {
            return annotationName;
        }
        if (parameter.isAnnotationPresent(AuthenticationPrincipal.class)) {
            return "authenticatedUser";
        }
        String reflectedName = parameter.getName();
        return reflectedName.startsWith("arg")
                ? parameter.getType().getSimpleName() + index
                : reflectedName;
    }

    private String annotationName(Parameter parameter) {
        PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return firstText(pathVariable.name(), pathVariable.value());
        }
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            return firstText(requestParam.name(), requestParam.value());
        }
        RequestHeader requestHeader = parameter.getAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return firstText(requestHeader.name(), requestHeader.value());
        }
        RequestPart requestPart = parameter.getAnnotation(RequestPart.class);
        if (requestPart != null) {
            return firstText(requestPart.name(), requestPart.value());
        }
        return null;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private String uniqueName(ObjectNode root, String name, int index) {
        return root.has(name) ? name + index : name;
    }

    private String abbreviate(String value) {
        if (value.length() <= maxPayloadLength) {
            return value;
        }
        return value.substring(0, maxPayloadLength)
                + "...(truncated,totalLength=" + value.length() + ")";
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 仅接受项目约定的数字用户 ID；其他 Principal 不调用 toString，避免意外写入身份详情。
     */
    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Number ? principal.toString() : "anonymous";
    }
}
