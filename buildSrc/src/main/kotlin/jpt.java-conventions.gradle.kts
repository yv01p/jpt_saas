plugins {
    java
}

group = "org.jphototagger"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        resources.srcDirs("src/main/java")
        resources.include("**/*.properties", "**/*.xml")
        // After each module migration, verify no other resource types are co-located:
        // find <module>/src/main/java -type f ! -name '*.java' ! -name '*.properties' ! -name '*.xml'
        // Add any discovered extensions to this include list.
    }
    test {
        resources.srcDirs("src/test/java")
        resources.include("**/*.properties", "**/*.xml", "**/*.html", "**/*")
        resources.exclude("**/*.java")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // Version catalog (libs) is not accessible in buildSrc convention plugins — keep in sync with libs.versions.toml
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
}
