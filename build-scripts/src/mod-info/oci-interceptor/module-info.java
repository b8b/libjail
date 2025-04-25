module org.cikit.oci.interceptor {
    requires kotlinx.coroutines.core;
    requires ktoml.core;

    requires transitive kotlin.stdlib;
    requires transitive kotlinx.serialization.core;
    requires transitive kotlinx.serialization.json;
    requires transitive com.github.ajalt.mordant.core;
    requires transitive com.github.ajalt.clikt;
    requires transitive org.cikit.libjail;

    exports org.cikit.oci;
    exports org.cikit.oci.jail;
}
