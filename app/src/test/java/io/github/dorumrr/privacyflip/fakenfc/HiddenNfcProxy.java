package io.github.dorumrr.privacyflip.fakenfc;

import java.util.ArrayList;
import java.util.List;

/** Shaped like INfcAdapter.Stub.Proxy: public methods on a class other packages cannot see. */
public final class HiddenNfcProxy {
    private HiddenNfcProxy() {}

    public static Object create(List<String> calls) {
        return new Proxy(calls);
    }

    static final class Proxy {
        private final List<String> calls;

        Proxy(List<String> calls) {
            this.calls = calls;
        }

        public boolean disable(boolean saveState, String pkg) {
            calls.add("disable(" + saveState + ", " + pkg + ")");
            return true;
        }

        public boolean enable(String pkg) {
            calls.add("enable(" + pkg + ")");
            return true;
        }
    }
}
