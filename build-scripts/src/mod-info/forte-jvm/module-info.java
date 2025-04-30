module org.cikit.forte {
    requires kotlinx.coroutines.core;
    requires kotlinx.io.bytestring;

    exports org.cikit.forte;
    exports org.cikit.forte.core;
    exports org.cikit.forte.emitter;
    exports org.cikit.forte.parser;
}
