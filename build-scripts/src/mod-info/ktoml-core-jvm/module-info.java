module ktoml.core {
    requires kotlin.stdlib;
    requires kotlinx.serialization.core;
    requires kotlinx.serialization.json;
    requires kotlinx.datetime;

    exports com.akuleshov7.ktoml;
    exports com.akuleshov7.ktoml.annotations;
    exports com.akuleshov7.ktoml.decoders;
    exports com.akuleshov7.ktoml.encoders;
    exports com.akuleshov7.ktoml.exceptions;
    exports com.akuleshov7.ktoml.parsers;
    exports com.akuleshov7.ktoml.tree.nodes;
    exports com.akuleshov7.ktoml.tree.nodes.pairs.keys;
    exports com.akuleshov7.ktoml.tree.nodes.pairs.values;
    exports com.akuleshov7.ktoml.utils;
    exports com.akuleshov7.ktoml.writers;
}
