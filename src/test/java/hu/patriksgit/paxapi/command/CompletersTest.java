ackage hu.patriksgit.paxapi.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompletersTest {
    enum Mode { ON, OFF, STATUS }

    @Test void ofReturnsStaticList() {
        ArgumentCompleter<Object> c = Completers.of("a", "b");
        assertEquals(List.of("a", "b"), c.complete(new Object(), new String[0], ""));
    }

    @Test void enumValuesAreLowercased() {
        ArgumentCompleter<Object> c = Completers.enumValues(Mode.class);
        assertEquals(List.of("on", "off", "status"), c.complete(new Object(), new String[0], ""));
    }

    @Test void listFromSupplier() {
        ArgumentCompleter<Object> c = Completers.list(() -> List.of("x", "y"));
        assertEquals(List.of("x", "y"), c.complete(new Object(), new String[0], ""));
    }
}
