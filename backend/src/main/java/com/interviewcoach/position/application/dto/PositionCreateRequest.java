package com.interviewcoach.position.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文本方式创建个人或公共岗位的 HTTP 请求体。
 * 应用层会先规范化 JD，再由登记事务创建岗位记录和一条初始为 WAITING 的当前解析任务。
 */
@Data
public class PositionCreateRequest {

    /** 调用方填写的岗位名称；去除首尾空白后仍必须有内容。 */
    @NotBlank(message = "岗位名称不能为空")
    private String positionName;

    /** JD 中的公司名称；请求可以不提供，登记时空白值转为空值。 */
    private String companyName;

    /** JD 中的工作地点；请求可以不提供，登记时空白值转为空值。 */
    private String location;

    /** JD 中的薪资描述原文；请求可以不提供，登记时空白值转为空值。 */
    private String salaryRange;

    /** 岗位大类编码，用于后续模型分析上下文和接口展示；去除首尾空白后仍必须有内容。 */
    @NotBlank(message = "岗位大类不能为空")
    private String jobCategory;

    /** 调用方提供的岗位职级编码或文本；可以为空，确认正式画像时可能由画像中的职级更新。 */
    private String level;

    /** 调用方粘贴的 JD 原文；应用层统一处理 BOM、换行、首尾空白和 Unicode 字符上限。 */
    @NotBlank(message = "JD 描述不能为空")
    private String jdContent;
}
