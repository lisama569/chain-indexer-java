package com.github.lisama569.chainindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 索引器可调参数。
 *
 * @param reorgAheadMargin 触发重组所需的最小领先高度（&gt;=1）。
 *                         等长或更短的竞争链视为分叉抖动，仅暂存不重组。
 */
@Component
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    /** 竞争链末端高度需领先规范链末端至少该值才触发重组。 */
    private int reorgAheadMargin = 1;

    public int getReorgAheadMargin() {
        return reorgAheadMargin;
    }

    public void setReorgAheadMargin(int reorgAheadMargin) {
        if (reorgAheadMargin < 1) {
            throw new IllegalArgumentException("indexer.reorg-ahead-margin must be >= 1");
        }
        this.reorgAheadMargin = reorgAheadMargin;
    }
}
