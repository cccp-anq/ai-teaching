package com.aijiaoxue.videoreact;

import com.aijiaoxue.videoreact.asr.config.AsrProperties;
import com.aijiaoxue.videoreact.asr.config.CourseHotwordProperties;
import com.aijiaoxue.videoreact.asr.staging.StagingProperties;
import com.aijiaoxue.videoreact.job.config.ContractProperties;
import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 爱教学 · 转写模块（《三项目公共接口契约 v0.1》实现）。
 *
 * <p>对外只提供契约 §2 的三个路由：
 * <pre>
 * POST /api/v1/jobs            提交转写任务（首次 202，幂等复用 200）
 * GET  /api/v1/jobs/{jobId}    查询任务信封
 * GET  /health                 健康与能力边界
 * </pre>
 *
 * <p>职责边界（契约 §1）：只处理主项目已分配的媒体、返回标准结果，
 * <b>不访问主项目数据库、不决定权限、不生成课程报告、不做抬头率</b>。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AsrProperties.class,
        CourseHotwordProperties.class,
        MediaProperties.class,
        ContractProperties.class,
        StagingProperties.class})
public class VideoReactAsrApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoReactAsrApplication.class, args);
    }
}
