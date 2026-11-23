package io.casehub.connectors;

import java.util.List;

public final class PaginationHelper {

    private PaginationHelper() {}

    public static <T> Page<T> paginate(List<T> all, PageRequest pagination) {
        int start = 0;
        if (pagination.cursor() != null && !pagination.cursor().isEmpty()) {
            start = Integer.parseInt(pagination.cursor());
        }
        int     size       = pagination.pageSize() > 0 ? pagination.pageSize() : 20;
        int     end        = Math.min(start + size, all.size());
        var     items      = all.subList(start, end);
        boolean hasMore    = end < all.size();
        String  nextCursor = hasMore ? String.valueOf(end) : null;
        return new Page<>(items, nextCursor, hasMore);
    }
}
