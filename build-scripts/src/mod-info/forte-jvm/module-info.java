module org.cikit.forte {
    requires kotlinx.coroutines.core;
    requires kotlinx.io.bytestring;
    requires kotlinx.serialization.core;
    requires kotlinx.serialization.json;
    requires kotlinx.collections.immutable;

    exports org.cikit.forte;
    exports org.cikit.forte.core;
    exports org.cikit.forte.emitter;
    exports org.cikit.forte.lib.common;
    exports org.cikit.forte.lib.core;
    exports org.cikit.forte.lib.jinja;
    exports org.cikit.forte.lib.python;
    exports org.cikit.forte.lib.salt;
    exports org.cikit.forte.parser;
}
