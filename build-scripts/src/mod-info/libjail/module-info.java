module org.cikit.libjail {
	requires kotlinx.coroutines.core;

	requires transitive kotlin.stdlib;
	requires transitive kotlinx.serialization.core;
	requires transitive kotlinx.serialization.json;

	exports org.cikit.libjail;
}
