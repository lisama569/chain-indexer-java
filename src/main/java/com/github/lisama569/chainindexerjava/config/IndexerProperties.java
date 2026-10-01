package com.github.lisama569.chainindexerjava.config;

import org.slf4j.Logger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 索引器可调配置（前缀 {@code indexer}）。
 * <p>
 * {@code indexer.reorg-min-lead}：换链门槛——竞争链的链尖高度至少要比当前规范链
 * 高出这么多块，才允许回滚接管。默认 {@value #DEFAULT_REORG_MIN_LEAD}（严格更长即接管）。
 * <p>
 * 为了能对非法配置给出明确、可读的提示并退回安全默认（而不是绑定失败直接崩溃，
 * 或悄悄用一个错误值继续跑），该属性按字符串绑定，由
 * {@link #sanitizeMinLead(String, Logger)} 统一解析校验：
 * 非数字、小于 1 的值都会打印 WARN 并回退到默认 1。
 */
@Component
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    /** 安全默认：竞争链只要严格更长（领先 1 块）即可接管。 */
    public static final int DEFAULT_REORG_MIN_LEAD = 1;

    /** 换链门槛原始值（字符串绑定以容忍非法输入）。 */
    private String reorgMinLead = String.valueOf(DEFAULT_REORG_MIN_LEAD);

    public String getReorgMinLead() {
        return reorgMinLead;
    }

    public void setReorgMinLead(String reorgMinLead) {
        this.reorgMinLead = reorgMinLead;
    }

    /** 解析并校验换链门槛；非法值打印可读 WARN 并回退默认 {@value #DEFAULT_REORG_MIN_LEAD}。 */
    public static int sanitizeMinLead(String raw, Logger log) {
        if (raw == null || raw.isBlank()) {
            log.warn("配置 indexer.reorg-min-lead 为空，非法：必须是 >= 1 的整数；"
                    + "回退安全默认 {}", DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
        try {
            return sanitizeMinLead(Integer.parseInt(raw.trim()), log);
        } catch (NumberFormatException e) {
            log.warn("配置 indexer.reorg-min-lead={} 非法：不是整数；"
                    + "回退安全默认 {}", raw, DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
    }

    /** 校验已解析的门槛值；小于 1 视为非法，打印可读 WARN 并回退默认。 */
    public static int sanitizeMinLead(int value, Logger log) {
        if (value < 1) {
            log.warn("配置 indexer.reorg-min-lead={} 非法：必须 >= 1；"
                    + "回退安全默认 {}", value, DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
        return value;
    }
}
