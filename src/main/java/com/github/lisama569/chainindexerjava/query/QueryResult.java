package com.github.lisama569.chainindexerjava.query;

/**
 * 查询响应信封：明确标注该结果对应的快照版本、高度与链标识。
 * 同一信封内的 data 全部来自同一个不可变快照，保证结果内部自洽、可解释。
 */
public record QueryResult<T>(long version,
                            long tipHeight,
                            String chainId,
                            T data) {

    public static <T> QueryResult<T> of(long version, long tipHeight, String chainId, T data) {
        return new QueryResult<>(version, tipHeight, chainId, data);
    }
}
