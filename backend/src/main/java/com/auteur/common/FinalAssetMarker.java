package com.auteur.common;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 资产 final 标记互斥工具:同一组同类资产里只允许一条 isFinal=true。
 * 调用方负责把 current 自身的 isFinal 已设好,本工具只负责清掉同组其它资产。
 */
public final class FinalAssetMarker {
    private FinalAssetMarker() {}

    /**
     * 在一组同类资产中,把当前资产标为 final;若 markFinal=true,清除其它同组资产的 final 标记并保存。
     *
     * @param current     本次刚生成/选定的资产(其 isFinal 已被设为 markFinal)
     * @param siblings    同 script(或同上下文)下所有同类资产(由 caller 查出来,允许包含 current 自身)
     * @param idOf        从资产里取主键,用来排除 current
     * @param setIsFinal  把 isFinal 写到资产的 setter
     * @param save        持久化资产的 callback(通常是 repository::save)
     * @param markFinal   true 时才执行清除
     * @param <T>         资产类型
     * @param <ID>        主键类型
     */
    public static <T, ID> void clearOthers(T current,
                                           Collection<T> siblings,
                                           Function<T, ID> idOf,
                                           BiConsumer<T, Boolean> setIsFinal,
                                           Consumer<T> save,
                                           boolean markFinal) {
        if (!markFinal) return;
        ID currentId = idOf.apply(current);
        for (T other : siblings) {
            ID otherId = idOf.apply(other);
            if (currentId == null || !currentId.equals(otherId)) {
                setIsFinal.accept(other, false);
                save.accept(other);
            }
        }
    }
}
