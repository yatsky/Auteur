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
