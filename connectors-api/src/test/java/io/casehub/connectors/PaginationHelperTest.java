package io.casehub.connectors;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PaginationHelperTest {

    @Test
    void paginatesFromStart() {
        var items = List.of("a", "b", "c", "d", "e");
        var page = PaginationHelper.paginate(items, new PageRequest(null, 3));
        assertThat(page.items()).containsExactly("a", "b", "c");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("3");
    }

    @Test
    void paginatesFromCursor() {
        var items = List.of("a", "b", "c", "d", "e");
        var page = PaginationHelper.paginate(items, new PageRequest("3", 3));
        assertThat(page.items()).containsExactly("d", "e");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void defaultsPageSizeTo20() {
        var items = List.of("a", "b", "c");
        var page = PaginationHelper.paginate(items, new PageRequest(null, 0));
        assertThat(page.items()).containsExactly("a", "b", "c");
        assertThat(page.hasMore()).isFalse();
    }


    @Test
    void emptyCursorTreatedAsFirstPage() {
        var items = List.of("a", "b", "c", "d", "e");
        var page  = PaginationHelper.paginate(items, new PageRequest("", 3));
        assertThat(page.items()).containsExactly("a", "b", "c");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("3");
    }

    @Test
    void emptyList() {
        var page = PaginationHelper.paginate(List.<String>of(), new PageRequest(null, 10));
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }
}
