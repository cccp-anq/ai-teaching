package com.aijiaoxue.videoreact.job.service;

import com.aijiaoxue.videoreact.job.config.ContractProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结果体积上限。
 *
 * <p>契约 §6 要求「双方实现请求/结果体积…上限并在部署交接表填数值」，
 * 并明确「本版完整结果单次返回，不私自分页/截断；长媒体如超限，
 * 先协商提升边界或用多个独立 range job」。
 *
 * <p>因此这里的策略是<b>拒绝而不是截断</b>：截断会让主项目拿到一份
 * "看起来完整、实际缺片段"的结果，覆盖统计和证据引用都会错，
 * 而它无法从响应中察觉。宁可明确失败，让主项目改用范围更小的 job。
 *
 * <p>检查点在<b>写入终态之前</b>：任务已 FAILED 时不会留下半个结果。
 */
@Component
public class ResultSizeGuard {

    private static final Logger log = LoggerFactory.getLogger(ResultSizeGuard.class);

    private final ContractProperties contract;

    public ResultSizeGuard(ContractProperties contract) {
        this.contract = contract;
    }

    /**
     * 校验结果规模。
     *
     * @param segmentCount 片段数
     * @param payloadJson  载荷 JSON 文本
     * @return 不合规说明；为空表示通过
     */
    public String check(int segmentCount, String payloadJson) {
        int maxSegments = contract.maxSegments();
        if (maxSegments > 0 && segmentCount > maxSegments) {
            log.warn("结果片段数 {} 超过本部署上限 {}", segmentCount, maxSegments);
            return "转写片段数 " + segmentCount + " 超过本部署上限 " + maxSegments
                    + "（不截断，请改用范围更小的 analysisRange 拆分为多个 job）";
        }
        return null;
    }

    /**
     * 供 /health 与交接表读取的上限数值。
     *
     * @return 最大片段数
     */
    public int maxSegments() {
        return contract.maxSegments();
    }
}
