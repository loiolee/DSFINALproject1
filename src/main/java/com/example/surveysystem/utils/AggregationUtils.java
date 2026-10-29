package com.example.surveysystem.utils;

import org.bson.types.Decimal128;

/**
 * MongoDB 聚合结果的取值工具。
 *
 * <p>聚合管道算出来的数值类型不稳定：{@code $sum} 可能给 Integer 或 Long，{@code $avg} 给 Double，
 * 涉及大数时可能是 Decimal128。业务代码如果直接强转就会在某个数据量上突然抛 ClassCastException，
 * 所以统一在这里做一次归一化。</p>
 *
 * <p>第二阶段把这段逻辑从 {@code StatsService} 里抽出来共用：跨问卷统计（{@code CrossSurveyStatsService}）
 * 需要完全相同的转换，复制一份的话，将来补一种数值类型就得记得改两处。</p>
 */
public final class AggregationUtils {

    private AggregationUtils() {
    }

    /** 转成 long；非数值一律当 0，避免聚合没有结果时抛空指针。 */
    public static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 转成 Double；非数值返回 null，调用方据此判断「没有数据」而不是「平均值是 0」。 */
    public static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Decimal128 decimal128) {
            return decimal128.bigDecimalValue().doubleValue();
        }
        return null;
    }
}
