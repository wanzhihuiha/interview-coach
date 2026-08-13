package com.interviewcoach.user.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewcoach.user.application.dto.ChangePasswordRequest;
import com.interviewcoach.user.application.dto.LoginRequest;
import com.interviewcoach.user.application.dto.RegisterRequest;
import com.interviewcoach.user.application.dto.UserProfileUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证用户 HTTP 接口的请求映射、DTO 序列化、认证身份传递和统一响应包装。
 *
 * <p>测试通过真实注册与登录端点取得 JWT，再访问资料和改密端点；Spring 测试事务结束后回滚持久化数据。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

    /** 在完整 Spring MVC 过滤链上发起请求并读取状态与响应 JSON 的测试客户端。 */
    @Autowired
    private MockMvc mockMvc;

    /** 将请求 DTO 写为 JSON，并从登录响应中提取 JWT 的测试序列化器。 */
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRegisterAndLogin() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername("apitest");
        registerRequest.setPassword("Password123");
        registerRequest.setConfirmPassword("Password123");

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.username").value("apitest"));

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("apitest");
        loginRequest.setPassword("Password123");

        MvcResult result = mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("apitest"));
    }

    @Test
    void shouldReturnErrorWhenUsernameExists() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("dupuser");
        request.setPassword("Password123");
        request.setConfirmPassword("Password123");

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void shouldUpdateProfile() throws Exception {
        String token = obtainToken("updateuser");

        UserProfileUpdateRequest updateRequest = new UserProfileUpdateRequest();
        updateRequest.setNickname("新昵称");
        updateRequest.setBio("Java 开发工程师");

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.profile.bio").value("Java 开发工程师"));
    }

    @Test
    void shouldChangePassword() throws Exception {
        String token = obtainToken("pwduser");

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("Password123");
        request.setNewPassword("NewPassword123");
        request.setConfirmPassword("NewPassword123");

        mockMvc.perform(put("/api/v1/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String obtainToken(String username) throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername(username);
        registerRequest.setPassword("Password123");
        registerRequest.setConfirmPassword("Password123");

        mockMvc.perform(post("/api/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(username);
        loginRequest.setPassword("Password123");

        MvcResult result = mockMvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
