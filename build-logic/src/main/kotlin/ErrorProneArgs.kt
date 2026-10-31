// The Error Prone flags every module compiles under.
val SHARED_ERRORPRONE_ARGS: List<String> = listOf(
    // Off by default. A type whose static initializer builds one of its own subtypes deadlocks when
    // two threads start initializing from either end.
    "-Xep:ClassInitializationDeadlock:ERROR",
    // The checks below flag what this code does on purpose: identity-keyed memos, private arrays
    // indexed by enum ordinal, the transport tables' static-import DSL (from, to, when), concrete
    // collection types chosen for their guarantees, and records carrying arrays.
    "-Xep:ReferenceEquality:OFF",
    "-Xep:EnumOrdinal:OFF",
    "-Xep:BadImport:OFF",
    "-Xep:NonApiType:OFF",
    "-Xep:ArrayRecordComponent:OFF",
    // Parsers rely on String.split dropping trailing empty fields, and the suggested fix needs Guava.
    "-Xep:StringSplitter:OFF",
    // Lombok, where a module uses it, copies a field's Javadoc onto generated accessors and mangles
    // inline tags on the way.
    "-Xep:UnrecognisedJavadocTag:OFF",
    // Javadoc is written only where it adds something, so a member without a summary is not a defect.
    "-Xep:MissingSummary:OFF",
    // TimeZoneUsage wants an injected Clock, but every Instant.now() here measures elapsed time;
    // the formats EagerStringFormatting flags need widths and precisions SLF4J's {} cannot express.
    "-Xep:TimeZoneUsage:OFF",
    "-Xep:EagerStringFormatting:OFF",
    // Error Prone Support's house-style checks: none of their findings is a defect.
    "-Xep:JUnitClassModifiers:OFF",
    "-Xep:StaticImport:OFF",
    "-Xep:NonStaticImport:OFF",
    "-Xep:LexicographicalAnnotationListing:OFF",
    "-Xep:LexicographicalAnnotationAttributeListing:OFF",
    "-Xep:LexicographicalPermitsListing:OFF",
    "-Xep:CanonicalAnnotationSyntax:OFF",
    // A suggestion only prints a note; as a warning, -Werror fails it, so every check either fails
    // the build or is switched off above.
    "-XepAllSuggestionsAsWarnings",
)
