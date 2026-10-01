package com.github.lisama569.chainindexerjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 索引器可调配置。
 * <p>
 * 目前包含"换链门槛" {@code indexer.reorg.min-lead}：
 * 竞争链的链尖高度必须比当前规范链尖<strong>至少领先该值</strong>，才允许接管（重组）。
 * 默认 {@value #DEFAULT_REORG_MIN_LEAD}，即只要竞争链严格更长（哪怕只多 1 块）就接管。
 * <p>
 * 非法值（小于 1 或无法解析）不会被静默采用：打印明确告警并退回安全默认
 * {@value #DEFAULT_REORG_MIN_LEAD}。运行期可通过 {@link #setReorgMinLead(long)} 调整，
 * 调整立即对后续摄入判定生效。
 */
@Component
public class IndexerProperties {

    private static final Logger log = LoggerFactory.getLogger(IndexerProperties.class);

    /** 配置项 key */
    public static final String REORG_MIN_LEAD_KEY = "indexer.reorg.min-lead";
    /** 安全默认：严格更长即接管 */
    public static final long DEFAULT_REORG_MIN_LEAD = 1L;

    /** 当前生效的换链门槛（volatile 保证运行期调整对读者立即可见）。 */
    private volatile long reorgMinLead;

    /** Spring 注入：从环境读取配置，非法值告警并退回安全默认。 */
    @Autowired
    public IndexerProperties(Environment env) {
        String raw = env.getProperty(REORG_MIN_LEAD_KEY);
        this.reorgMinLead = parse(raw);
    }

    /** 测试/手工构造：直接给定门槛值（同样走校验）。 */
    public IndexerProperties(long reorgMinLead) {
        this.reorgMinLead = sanitize(reorgMinLead);
    }

    public long reorgMinLead() {
        return reorgMinLead;
    }

    /**
     * 运行期调整换链门槛。非法值不生效：打印告警并保持/退回安全默认。
     *
     * @return 调整后的生效值
     */
    public long setReorgMinLead(long value) {
        long sanitized = sanitize(value);
        if (sanitized != this.reorgMinLead) {
            log.warn("换链门槛调整: {} -> {}（竞争链须领先规范链至少 {} 块才允许接管）",
                    this.reorgMinLead, sanitized, sanitized);
        }
        this.reorgMinLead = sanitized;
        return this.reorgMinLead;
    }

    private static long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            log.info("未配置 {}，使用安全默认 {}", REORG_MIN_LEAD_KEY, DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
        try {
            return sanitize(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("配置 {}={} 无法解析为整数，退回安全默认 {}（合法取值：>=1 的整数）",
                    REORG_MIN_LEAD_KEY, raw, DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
    }

    /** 校验门槛值：小于 1 为非法，打印可读告警并退回安全默认。 */
    private static long sanitize(long value) {
        if (value < 1) {
            log.warn("换链门槛 {} 非法（必须 >= 1），退回安全默认 {}", value, DEFAULT_REORG_MIN_LEAD);
            return DEFAULT_REORG_MIN_LEAD;
        }
        return value;
    }
}
