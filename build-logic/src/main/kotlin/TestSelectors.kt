// A surefire-style test selector (FooTest, Bar*IT, FooIT#method, a.b.FooTest) as a Gradle test
// filter pattern.
fun surefireSelectorToPattern(selector: String): String {
    val (cls, method) = selector.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
    val qualified = if ('.' in cls) cls else "*.$cls"
    return if (method != null) "$qualified.$method" else qualified
}

// A comma-separated list of selectors, as -Dtest takes them; empty for null.
fun surefireSelectors(value: String?): List<String> =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(::surefireSelectorToPattern) ?: emptyList()
